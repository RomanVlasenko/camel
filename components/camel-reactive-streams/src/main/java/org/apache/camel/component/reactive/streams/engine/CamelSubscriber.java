/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.reactive.streams.engine;

import java.io.Closeable;
import java.io.IOException;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.reactive.streams.ReactiveStreamsConsumer;
import org.apache.camel.component.reactive.streams.api.CamelReactiveStreamsService;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Camel subscriber. It bridges messages from reactive streams to Camel routes.
 */
public class CamelSubscriber implements Subscriber<Exchange>, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(CamelSubscriber.class);

    /**
     * Enough to be considered unbounded. Requests are refilled once completed.
     */
    private static final long MAX_INFLIGHT_UNBOUNDED = Long.MAX_VALUE / 2;

    private ReactiveStreamsConsumer consumer;

    private Subscription subscription;

    private String name;

    private long requested;

    private long inflightCount;

    public CamelSubscriber(String name) {
        this.name = name;
    }

    public void attachConsumer(ReactiveStreamsConsumer consumer) {
        synchronized (this) {
            if (this.consumer != null) {
                throw new IllegalStateException("A consumer is already attached to the stream '" + name + "'");
            }
            this.consumer = consumer;
        }
        refill();
    }

    public void detachConsumer() {
        synchronized (this) {
            this.consumer = null;
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        if (subscription == null) {
            throw new NullPointerException("subscription is null for stream '" + name + "'");
        }

        boolean allowed = true;
        synchronized (this) {
            if (this.subscription != null) {
                allowed = false;
            } else {
                this.subscription = subscription;
            }
        }

        if (!allowed) {
            subscription.cancel();
        } else {
            refill();
        }
    }

    @Override
    public void onNext(Exchange exchange) {
        if (exchange == null) {
            throw new NullPointerException("exchange is null");
        }

        ReactiveStreamsConsumer target;
        synchronized (this) {
            requested--;
            target = this.consumer;
            if (target != null) {
                inflightCount++;
            }
        }

        if (target != null) {
            target.process(exchange, doneSync -> {
                synchronized (this) {
                    inflightCount--;
                }

                refill();
            });
        } else {
            // This may happen when the consumer is stopped
            LOG.warn("Message received in stream '{}', but no consumers were attached. Discarding {}.", name, exchange);
        }
    }

    protected void refill() {
        Long toBeRequested = null;
        Subscription subs = null;
        synchronized (this) {
            if (consumer != null && this.subscription != null) {
                Integer consMax = consumer.getEndpoint().getMaxInflightExchanges();
                long max = (consMax != null && consMax > 0) ? consMax.longValue() : MAX_INFLIGHT_UNBOUNDED;
                long newRequest = max - requested - inflightCount;
                if (newRequest > 0) {
                    toBeRequested = newRequest;
                    requested += toBeRequested;
                    subs = this.subscription;
                }
            }
        }

        if (toBeRequested != null) {
            subs.request(toBeRequested);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        if (throwable == null) {
            throw new NullPointerException("throwable is null");
        }

        LOG.error("Error in reactive stream '" + name + "'", throwable);

        ReactiveStreamsConsumer consumer;
        synchronized (this) {
            consumer = this.consumer;
            this.subscription = null;
        }

        if (consumer != null) {
            consumer.onError(throwable);
        }

    }

    @Override
    public void onComplete() {
        LOG.info("Reactive stream '{}' completed", name);

        ReactiveStreamsConsumer consumer;
        synchronized (this) {
            consumer = this.consumer;
            this.subscription = null;
        }

        if (consumer != null) {
            consumer.onComplete();
        }
    }

    @Override
    public void close() throws IOException {
        Subscription subscription;
        synchronized (this) {
            subscription = this.subscription;
        }

        if (subscription != null) {
            subscription.cancel();
        }
    }

}
