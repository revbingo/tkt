package com.revbingo.aws

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.subject.SubjectSpek
import software.amazon.awssdk.regions.Region
import java.io.File

class PricingProviderTest: SubjectSpek<PricingProvider>({

    subject { EC2InstancesDotInfoPricingProvider(File("test-data/instances.json")) }

    val testAccount = Location(Profile("test"), Region.EU_WEST_1)
    
    describe("the pricing provider") {
        it("depends on instance type") {
            val m1Small = MatchedInstance(instance(type = "m1.small", az = "eu-west-1a"), testAccount)
            val m1Medium = MatchedInstance(instance(type = "m1.medium", az = "eu-west-1a"), testAccount)

            assertThat(subject.getPriceFor(m1Small), equalTo(0.047f))
            assertThat(subject.getPriceFor(m1Medium), equalTo(0.095f))
        }

        it("depends on OS") {
            val windows = MatchedInstance(instance(type = "m1.small", az = "eu-west-1a", platform = "Windows"), testAccount)
            val linux = MatchedInstance(instance(type = "m1.small", az = "eu-west-1a", platform = "Linux/UNIX"), testAccount)

            assertThat(subject.getPriceFor(linux), equalTo(0.047f))
            assertThat(subject.getPriceFor(windows), equalTo(0.075f))
        }

        it("depends on reservation") {
            val unreserved = MatchedInstance(instance(type = "m1.small", az = "eu-west-1a"), testAccount)
            val reserved = MatchedInstance(instance(type = "m1.small", az = "eu-west-1a"), testAccount)
            reserved.matched = true

            assertThat(subject.getPriceFor(reserved), equalTo(0.034f))
            assertThat(subject.getPriceFor(unreserved), equalTo(0.047f))
        }

        it("depends on region") {
            val eu = MatchedInstance(instance(type = "m1.small", az = "eu-west-1a", platform = "Linux/UNIX"), testAccount)
            val us = MatchedInstance(instance(type = "m1.small", az = "us-west-2a", platform = "Linux/UNIX"), testAccount)

            assertThat(subject.getPriceFor(eu), equalTo(0.047f))
            assertThat(subject.getPriceFor(us), equalTo(0.044f))
        }

        it("returns zero when instance type doesn't exist") {
            val newtype = MatchedInstance(instance(type = "m10.small", az = "eu-west-1a", platform = "Linux/UNIX"), testAccount)

            assertThat(subject.getPriceFor(newtype), equalTo(0.0f))
        }
    }
})

