/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.eval

import org.junit.runner.RunWith
import org.junit.runners.Suite

/** Test suite to run all local unit tests for the on-device evaluation app. */
@RunWith(Suite::class)
@Suite.SuiteClasses(
  MiniHttpServerTest::class,
  EvalServiceTest::class,
  EvalReceiverTest::class,
  PromptParserTest::class,
)
class EvalAppTestSuite
