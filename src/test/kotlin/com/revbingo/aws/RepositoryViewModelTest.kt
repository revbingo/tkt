package com.revbingo.aws

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.revbingo.db.DatabaseAccessor
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class RepositoryViewModelTest : Spek({

    val testAccount = Location(Profile("Test"), "eu-west-1")

    describe("the repository") {
        it("returns a count of running instances") {
            val mockRepo = mock<Repository> {
                on { it.instances } doReturn mutableListOf(matchedInstances(count = 3, state = "running"),
                                            matchedInstances(count = 2, state = "stopped")).flatten()
            }
            val subject = RepositoryViewModel(mockRepo)

            assertThat(subject.runningCount(), equalTo(3))
        }

        it("gives lastRefreshTime as a formatted date") {
            val currentTime = LocalDateTime.now()

            val mockRepo = mock<Repository> {
                on { it.updateTime } doReturn currentTime
                on { it.updating } doReturn false
            }
            val subject = RepositoryViewModel(mockRepo)

            assertThat(subject.lastRefreshTime(), equalTo(DateTimeFormatter.ISO_DATE_TIME.format(currentTime)))
        }

        it("says lastRefreshTime is Refreshing Now if the repository is updating") {
            val currentTime = LocalDateTime.now()

            val mockRepo = mock<Repository> {
                on { it.updateTime } doReturn currentTime
                on { it.updating } doReturn true
            }
            val subject = RepositoryViewModel(mockRepo)

            assertThat(subject.lastRefreshTime(), equalTo("(Refreshing now)"))
        }

        it("applies pricing information") {
            val instance1 = MatchedInstance(instance(az = "eu-west-1", type = "m1.small"), testAccount)
            val instance2 = MatchedInstance(instance(az = "eu-west-1", type = "m1.medium"), testAccount)

            val mockPricingProvider = mock<PricingProvider> {
                on { getPriceFor(instance1) } doReturn 0.050f
                on { getPriceFor(instance2) } doReturn 0.070f
            }
            val mockFetcher = mock<Fetcher> {
                on { getInstances() } doReturn listOf(instance1, instance2)
                on { getReservedInstances() } doReturn emptyList<CountedReservation>()
                on { getLoadBalancers() } doReturn emptyList<InstancedLoadBalancer>()
            }
            val mockDatabase = mock<DatabaseAccessor>()

            val subject = Repository(mockFetcher, mockPricingProvider, mockDatabase)

            subject.update()

            assertThat(instance1.price, equalTo(0.050f))
            assertThat(instance2.price, equalTo(0.070f))

        }

        it("gives total number of instances"){
            val mockRepo = mock<Repository> {
                on { it.instances } doReturn mutableListOf(matchedInstances(count = 3, state = "running"),
                        matchedInstances(count = 2, state = "stopped")).flatten()
            }
            val subject = RepositoryViewModel(mockRepo)

            assertThat(subject.instanceCount(), equalTo(5))
        }

        it("gives number of instances in vpc") {
            val mockRepo = mock<Repository> {
                on { it.instances } doReturn mutableListOf(matchedInstances(count = 3, state = "running"),
                        matchedInstances(count = 2, state = "running", vpcId = "vpc")).flatten()
            }
            val subject = RepositoryViewModel(mockRepo)

            assertThat(subject.vpcCount(), equalTo(2))
        }

        it("gives total number of reserved instances") {
            val mockRepo = mock<Repository> {
                on { it.reservedInstances } doReturn countedReservations(count = 4, az = "us-west-1", type = "m3.large", unmatchedCount = 1)
            }

            val subject = RepositoryViewModel(mockRepo)

            assertThat(subject.reservedCount(), equalTo(3))
        }

        it("gives total number of unmatched reservations") {
            val mockRepo = mock<Repository> {
                on { it.reservedInstances } doReturn countedReservations(count = 4, az = "us-west-1", type = "m3.large", unmatchedCount = 1)
            }

            val subject = RepositoryViewModel(mockRepo)

            assertThat(subject.unmatchedCount(), equalTo(1))
        }

        it("gives percentage widths for bars") {
            val mockRepo = mock<Repository> {
                on { it.reservedInstances } doReturn countedReservations(count = 6, az = "us-west-1", type = "m3.large", unmatchedCount = 1)
                on { it.instances } doReturn mutableListOf(
                                                matchedInstances(count = 5, state = "running"),
                                                matchedInstances(count = 2, state = "running", vpcId = "something"),
                                                matchedInstances(count = 2, state = "stopped")
                                            ).flatten()
            }

            val subject = RepositoryViewModel(mockRepo)

            assertThat(subject.instancePct(), equalTo(90))
            assertThat(subject.unmatchedPct(), equalTo(10))
            assertThat(subject.runningPct(), equalTo(70))
            assertThat(subject.reservedPct(), equalTo(50))
            assertThat(subject.vpcPct(), equalTo(20))
        }

        it("gives total cost per hour, including only running instances") {
            val instances = mutableListOf(matchedInstances(count = 7, state = "running"),
                    matchedInstances(count = 2, state = "stopped")).flatten()
            instances.forEachIndexed { i, matchedInstance -> matchedInstance.price = 0.1f * (i+1) }

            val mockRepo = mock<Repository> {
                on { it.instances } doReturn instances
            }

            val subject = RepositoryViewModel(mockRepo)
            assertThat(subject.formattedCost(), equalTo("2.80"))
        }

        it("indicates errors") {
            val mockRepo = mock<Repository> {
                on { it.inError } doReturn true
                on { it.errorMessage } doReturn "An error occurred"
            }

            val subject = RepositoryViewModel(mockRepo)

            assertThat(subject.inError(), equalTo(true))
            assertThat(subject.errorMessage(), equalTo("An error occurred"))
        }
    }
})