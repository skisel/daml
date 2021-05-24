// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.benchtool

import com.daml.ledger.api.benchtool.metrics.Metric.DelayMetric
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Random

class ServiceLevelObjectiveSpec extends AnyWordSpec with Matchers with TableDrivenPropertyChecks {
  "Maximum delay SLO" should {
    "correctly report violation" in {
      import DelayMetric.Value
      val randomValue = Random.nextLong(10000)
      val randomSmaller = Random.nextLong(randomValue)
      val randomLarger = randomValue + Random.nextLong(10000)
      val maxDelay = DelayMetric.DelayObjective.MaxDelay(randomValue)
      val cases = Table(
        ("Metric value", "Expected violated"),
        (Value(None), false),
        (Value(Some(randomSmaller)), false),
        (Value(Some(randomValue)), false),
        (Value(Some(randomLarger)), true),
      )

      forAll(cases) { (metricValue, expectedViolated) =>
        maxDelay.isViolatedBy(metricValue) shouldBe expectedViolated
      }
    }

    "correctly pick a value more violating requirements" in {
      import DelayMetric.Value
      val randomNumber = Random.nextLong(10)
      val higherNumber = randomNumber + 1
      val cases = Table(
        ("first", "second", "expected result"),
        (Value(Some(randomNumber)), Value(Some(higherNumber)), Value(Some(higherNumber))),
        (Value(Some(higherNumber)), Value(Some(randomNumber)), Value(Some(higherNumber))),
        (Value(Some(randomNumber)), Value(None), Value(Some(randomNumber))),
        (Value(None), Value(Some(randomNumber)), Value(Some(randomNumber))),
        (Value(None), Value(None), Value(None)),
      )
      val objective = DelayMetric.DelayObjective.MaxDelay(Random.nextLong())

      forAll(cases) { (first, second, expected) =>
        objective.moreViolatingOf(first, second) shouldBe expected
      }
    }
  }
}