package com.revbingo.aws

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it

class ReservationMatcherTest : Spek( {

    fun match(reservedInstances: List<List<CountedReservation>>, instances: List<List<MatchedInstance>>) : List<MatchedInstance> {
        val matchedInstances = ReservationMatcher(reservedInstances.flatten()).match(instances.flatten())
        return matchedInstances
    }

    describe("the matcher") {
        it("matches active instances of the same type and region") {
            val matchedInstances = match(
                listOf(
                    countedReservations(count = 3, az = "us-west-1a", type = "m3.large")
                ),
                listOf(
                    matchedInstances(count = 3, az = "us-west-1a", type = "m3.large")
                )
            )

            assertThat(matchedInstances.matching().count(), equalTo(3))
        }

        it("does not match if the region is different") {
            val matchedInstances = match(
                listOf(
                    countedReservations(az = "us-west-1a", type = "m3.large")
                ),
                listOf(
                    matchedInstances(az = "us-east-1a", type = "m3.large")
                )
            )

            assertThat(matchedInstances.matching().count(), equalTo(0))
        }

        it("matches instances in the same region if Region scope is set") {
            val matchedInstances = match(
                listOf(
                    countedReservations(count = 3, regionScope = true, region = "us-west-1", type = "m3.large")
                ),
                listOf(
                    matchedInstances(az = "us-west-1a", type = "m3.large"),
                    matchedInstances(az = "us-west-1b", type = "m3.large"),
                    matchedInstances(az = "us-east-1b", type = "m3.large")
                )
            )

            assertThat(matchedInstances.matching().count(), equalTo(2))
        }

        it("does not match if the type is different") {
            val matchedInstances = match(
                listOf(
                    countedReservations(az = "us-west-1a", type = "m3.small")
                ),
                listOf(
                    matchedInstances(az = "us-west-1a", type = "m3.large")
                )
            )

            assertThat(matchedInstances.matching().count(), equalTo(0))
        }

        it("only matches as many instances as are reserved") {
            val matchedInstances = match(
                listOf(
                    countedReservations(count = 2, az = "us-west-1a", type = "m3.large")
                ),
                listOf(
                    matchedInstances(count = 10, az = "us-west-1a", type = "m3.large")
                )
            )

            assertThat(matchedInstances.matching().count(), equalTo(2))
        }

        it("only matches active reservations") {
            val matchedInstances = match(
                listOf(
                    countedReservations(count = 2, az = "us-west-1a", type = "m3.large", state = "retired"),
                    countedReservations(count = 1, az = "us-west-1a", type = "m3.large")
                ),
                listOf(
                    matchedInstances(count = 2, az = "us-west-1a", type = "m3.large")
                )
            )

            assertThat(matchedInstances.matching().count(), equalTo(1))
        }

        it("only matches running instances") {
            val matchedInstances = match(
                listOf(
                    countedReservations(count = 2, az = "us-west-1a", type = "m3.large")
                ),
                listOf(
                    matchedInstances(count = 2, az = "us-west-1a", type = "m3.large", state = "stopped"),
                    matchedInstances(count = 1, az = "us-west-1a", type = "m3.large", state = "running")
                )
            )

            assertThat(matchedInstances.matching().count(), equalTo(1))
        }

        it("only consumes one reservation per instance") {
            val matchedInstances = match(
                listOf(
                    countedReservations(count = 1, az = "us-west-1a", type = "m3.large"),
                    countedReservations(count = 1, az = "us-west-1a", type = "m3.large")

                ),
                listOf(
                    matchedInstances(count = 2, az = "us-west-1a", type = "m3.large", state = "running")
                )
            )

            assertThat(matchedInstances.matching().count(), equalTo(2))
        }

        it("only matches if the platform matches") {
            val matchedInstances = match(
                listOf(
                    countedReservations(count = 2, az = "us-west-1a", type = "m3.large", product = "Linux/UNIX")
                ),
                listOf(
                    matchedInstances(count = 1, az = "us-west-1a", type = "m3.large", state = "running", id = "theLinuxInstance"),
                    matchedInstances(count = 1, az = "us-west-1a", type = "m3.large", state = "running", platform = "windows", id = "theWindowsInstance")
                )
            )

            assertThat(matchedInstances.matching().count(), equalTo(1))
            assertThat(matchedInstances.matching()[0].originalInstance.instanceId(), equalTo("theLinuxInstance"))
        }

        it("consumes compute units if region scope and same family") {
            val matchedInstances = match(
                    listOf(
                            countedReservations(count = 2, az = "us-west-1a", type = "t2.medium", product = "Linux/UNIX", regionScope = true)
                    ),
                    listOf(
                            matchedInstances(count = 1, az = "us-west-1a", type = "t2.small", state = "running", id = "aLinuxInstance"),
                            matchedInstances(count = 1, az = "us-west-1a", type = "t2.small", state = "running", id = "aLinuxInstance"),
                            matchedInstances(count = 1, az = "us-west-1a", type = "t2.small", state = "running", id = "aLinuxInstance"),
                            matchedInstances(count = 1, az = "us-west-1a", type = "t2.small", state = "running", id = "aLinuxInstance"),
                            matchedInstances(count = 1, az = "us-west-1a", type = "t2.small", state = "running", id = "aLinuxInstance")
                    )
            )

            assertThat(matchedInstances.matching().count(), equalTo(4))
        }

    }
})