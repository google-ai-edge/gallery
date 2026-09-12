/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.datatransport.runtime;

import android.annotation.SuppressLint;
import android.content.Context;
import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.Event;
import com.google.android.datatransport.Transformer;
import com.google.android.datatransport.Transport;
import com.google.android.datatransport.TransportFactory;
import com.google.android.datatransport.TransportScheduleCallback;
import com.google.android.datatransport.cct.CCTDestination;
import com.google.android.datatransport.runtime.scheduling.jobscheduling.Uploader;
import java.lang.reflect.Constructor;

/**
 * Firelog shim that delegates to GMS Clearcut SDK when present and supports both 1p
 * (CCTDestination) and 3p (Destination) newFactory method signatures.
 */
public class TransportRuntime {
  @SuppressLint("StaticFieldLeak")
  private static volatile TransportRuntime instance = null;

  private final Context context;

  private TransportRuntime(Context applicationContext) {
    this.context = applicationContext.getApplicationContext();
  }

  /**
   * Initializes transport runtime with an application context.
   *
   * <p>This method must be called before {@link #getInstance()}.
   */
  public static void initialize(Context applicationContext) {
    if (instance == null) {
      synchronized (TransportRuntime.class) {
        if (instance == null) {
          instance = new TransportRuntime(applicationContext);
        }
      }
    }
  }

  /**
   * Returns the global singleton instance of {@link TransportRuntime}.
   *
   * @throws IllegalStateException if {@link #initialize(Context)} is not called before this method.
   */
  public static TransportRuntime getInstance() {
    TransportRuntime localRef = instance;
    if (localRef == null) {
      throw new IllegalStateException("Not initialized!");
    }
    return localRef;
  }

  /**
   * Returns a factory that sends events via GMS Clearcut.
   *
   * @deprecated Use {@link #newFactory(Destination)} instead.
   */
  @Deprecated
  public TransportFactory newFactory(String unused) {
    return createFactory();
  }

  /** Returns a factory that sends events via GMS Clearcut (1p signature). */
  public TransportFactory newFactory(CCTDestination unused) {
    return createFactory();
  }

  /** Returns a factory that sends events via GMS Clearcut (3p signature). */
  public TransportFactory newFactory(Destination unused) {
    return createFactory();
  }

  /** Returns the uploader instance (stubbed for 3p jobscheduling callers). */
  public Uploader getUploader() {
    return null;
  }

  private TransportFactory createFactory() {
    try {
      Class<?> factoryClass =
          Class.forName("com.google.android.datatransport.runtime.ClearcutTransportFactory");
      Constructor<?> ctor = factoryClass.getDeclaredConstructor(Context.class);
      ctor.setAccessible(true);
      return (TransportFactory) ctor.newInstance(context);
    } catch (ReflectiveOperationException e) {
      return new TransportFactory() {
        @Override
        public <T> Transport<T> getTransport(
            String name, Class<T> payloadType, Transformer<T, byte[]> payloadTransformer) {
          return new NoOpTransport<>();
        }

        @Override
        public <T> Transport<T> getTransport(
            String name,
            Class<T> payloadType,
            Encoding payloadEncoding,
            Transformer<T, byte[]> payloadTransformer) {
          return new NoOpTransport<>();
        }
      };
    }
  }

  private static final class NoOpTransport<T> implements Transport<T> {
    @Override
    public void send(Event<T> event) {}

    @Override
    public void schedule(Event<T> event, TransportScheduleCallback callback) {
      callback.onSchedule(null);
    }
  }
}
