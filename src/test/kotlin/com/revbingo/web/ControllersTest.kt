package com.revbingo.web


import com.natpryce.hamkrest.absent
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.isA
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock

import com.revbingo.aws.*
import com.revbingo.db.DatabaseAccessor
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener
import software.amazon.awssdk.services.elasticloadbalancing.model.ListenerDescription
import software.amazon.awssdk.services.elasticloadbalancing.model.LoadBalancerDescription

class ControllersTest: Spek({

    fun List<ConfigHost>.containsHost(host: String): Boolean = this.find { it.host == host } != null

    describe("UI Controller") {

        it("puts repository into model when listing instances") {
            val mockRepo = mock<Repository>()

            val unit = UIController(mockRepo)

            val modelAndView = unit.repositoryListing("test.mustache")
            assertThat(modelAndView.model, isA<RepositoryViewModel>())
        }
    }

    describe("the ssh generator") {
        it("only includes running servers") {
            val repo = Repository(mock<Fetcher>(), mock<PricingProvider>(), mock<DatabaseAccessor>())
            repo.instances = listOf (
                    matchedInstances(state = "running", tags = listOf(tag("Name", "running1"))),
                    matchedInstances(state = "running", tags = listOf(tag("Name", "running2"))),
                    matchedInstances(state = "running", tags = listOf(tag("Name", "running3"))),
                    matchedInstances(state = "stopped", tags = listOf(tag("Name", "stopped1"))),
                    matchedInstances(state = "stopped", tags = listOf(tag("Name", "stopped2")))
            ).flatten()

            val subject = APIController(repo)
            val config = subject.generateSshConfig()

            val hosts = ConfigParser.parse(config)

            assertThat(hosts.size, equalTo(3))

            assertThat(hosts.containsHost("running1"), equalTo(true))
            assertThat(hosts.containsHost("running2"), equalTo(true))
            assertThat(hosts.containsHost("running3"), equalTo(true))
            assertThat(hosts.containsHost("stopped1"), equalTo(false))
            assertThat(hosts.containsHost("stopped2"), equalTo(false))
        }

        it("has ec2-user as the user if CloudFormation tag not present") {
            val repo = Repository(mock<Fetcher>(), mock<PricingProvider>(), mock<DatabaseAccessor>())
            repo.instances = listOf (
                    matchedInstances(state = "running", tags = listOf(tag("Name", "running1")))
            ).flatten()

            val subject = APIController(repo)
            val config = subject.generateSshConfig()

            val hosts = ConfigParser.parse(config)

            assertThat(hosts.size, equalTo(1))
            assertThat(hosts[0].user, equalTo("ec2-user"))
        }

        it("does not have a User (i.e. uses AD user name) or IdentityFile if the server was created by CloudFormation") {
            val repo = Repository(mock<Fetcher>(), mock<PricingProvider>(), mock<DatabaseAccessor>())
            repo.instances = listOf (
                    matchedInstances(state = "running", tags = listOf(tag("Name", "running1"), tag("aws:cloudformation:stack-name", "stack")))
            ).flatten()

            val subject = APIController(repo)
            val config = subject.generateSshConfig()

            val hosts = ConfigParser.parse(config)

            assertThat(hosts.size, equalTo(1))
            assertThat(hosts[0].host, equalTo("running1"))
            assertThat(hosts[0].user, absent())
            assertThat(hosts[0].identityFile, absent())
        }

        it("uses public IP for the HostName if available, otherwise private ip") {
            val repo = Repository(mock<Fetcher>(), mock<PricingProvider>(), mock<DatabaseAccessor>())
            repo.instances = listOf (
                    matchedInstances(state = "running", tags = listOf(tag("Name", "public1")), publicIpAddress = "1.2.3.4", privateIpAddress = "100.99.98.97"),
                    matchedInstances(state = "running", tags = listOf(tag("Name", "private1")), privateIpAddress = "100.99.98.97")
            ).flatten()

            val subject = APIController(repo)
            val config = subject.generateSshConfig()

            val hosts = ConfigParser.parse(config)

            assertThat(hosts.size, equalTo(2))
            assertThat(hosts.find { it.host == "public1"}?.hostName, equalTo("1.2.3.4"))
            assertThat(hosts.find { it.host == "private1"}?.hostName, equalTo("100.99.98.97"))
        }

        it("has StrictHostKeyChecking turned off") {
            val repo = Repository(mock<Fetcher>(), mock<PricingProvider>(), mock<DatabaseAccessor>())
            repo.instances = listOf (
                    matchedInstances(state = "running", tags = listOf(tag("Name", "public1")))
            ).flatten()

            val subject = APIController(repo)
            val config = subject.generateSshConfig()

            val hosts = ConfigParser.parse(config)

            assertThat(hosts[0].strictHostKeyChecking, equalTo("no"))
        }

        it("does not include Windows hosts") {
            val repo = Repository(mock<Fetcher>(), mock<PricingProvider>(), mock<DatabaseAccessor>())
            repo.instances = listOf (
                    matchedInstances(state = "running", platform = "Windows", tags = listOf(tag("Name", "windows"))),
                    matchedInstances(state = "running", platform = "Linux/Unix", tags = listOf(tag("Name", "linux")))
            ).flatten()

            val subject = APIController(repo)
            val config = subject.generateSshConfig()

            val hosts = ConfigParser.parse(config)

            assertThat(hosts.size, equalTo(1))
            assertThat(hosts[0].host, equalTo("linux"))
        }

        it("uses keyname for the IdentityFile") {
            val repo = Repository(mock<Fetcher>(), mock<PricingProvider>(), mock<DatabaseAccessor>())
            repo.instances = listOf (
                    matchedInstances(state = "running", keyName = "myPrivateKey", tags = listOf(tag("Name", "public")))
            ).flatten()

            val subject = APIController(repo)
            val config = subject.generateSshConfig()

            val hosts = ConfigParser.parse(config)

            assertThat(hosts.size, equalTo(1))
            assertThat(hosts[0].identityFile, equalTo("~/.ssh/myPrivateKey.pem"))
        }

        it("skips hosts that don't have a keyname") {
            val repo = Repository(mock<Fetcher>(), mock<PricingProvider>(), mock<DatabaseAccessor>())
            repo.instances = listOf (
                    matchedInstances(state = "running", keyName = null, tags = listOf(tag("Name", "nokey"))),
                    matchedInstances(state = "running", keyName = "akey", tags = listOf(tag("Name", "akey")))
            ).flatten()

            val subject = APIController(repo)
            val config = subject.generateSshConfig()

            val hosts = ConfigParser.parse(config)

            assertThat(hosts.size, equalTo(1))
            assertThat(hosts[0].host, equalTo("akey"))
        }

        it("lowercases names") {
            val repo = Repository(mock<Fetcher>(), mock<PricingProvider>(), mock<DatabaseAccessor>())
            repo.instances = listOf (
                    matchedInstances(tags = listOf(tag("Name", "UPPERCASE")))
            ).flatten()

            val subject = APIController(repo)
            val config = subject.generateSshConfig()

            val hosts = ConfigParser.parse(config)

            assertThat(hosts.size, equalTo(1))
            assertThat(hosts[0].host, equalTo("uppercase"))
        }

        it("replaces spaces with hyphens in the names") {
            val repo = Repository(mock<Fetcher>(), mock<PricingProvider>(), mock<DatabaseAccessor>())
            repo.instances = listOf (
                    matchedInstances(tags = listOf(tag("Name", "A Server With Spaces")))
            ).flatten()

            val subject = APIController(repo)
            val config = subject.generateSshConfig()

            val hosts = ConfigParser.parse(config)

            assertThat(hosts.size, equalTo(1))
            assertThat(hosts[0].host, equalTo("a-server-with-spaces"))
        }

        it("filters if an account is passed") {
            val repo = Repository(mock<Fetcher>(), mock<PricingProvider>(), mock<DatabaseAccessor>())
            repo.instances = listOf (
                    matchedInstances(tags = listOf(tag("Name", "devserver")), accountName = "Dev"),
                    matchedInstances(tags = listOf(tag("Name", "prodserver")), accountName = "Prod")
            ).flatten()

            val subject = APIController(repo)
            val config = subject.generateSshConfig(accountName = "Dev")

            val hosts = ConfigParser.parse(config)

            assertThat(hosts.size, equalTo(1))
            assertThat(hosts[0].host, equalTo("devserver"))
        }
    }

    describe("the load balancer listing") {
        val mockRepo = mock<Repository> {
            on { loadBalancers } doReturn listOf(
                    InstancedLoadBalancer(LoadBalancerDescription.builder()
                        .loadBalancerName("therightone")
                        .dnsName("therightone.amazon.com")
                        .listenerDescriptions(listOf(
                                ListenerDescription.builder()
                                    .listener(Listener.builder().protocol("HTTP").loadBalancerPort(80).instancePort(8080).build())
                                    .build()
                                ,
                                ListenerDescription.builder()
                                    .listener(Listener.builder().protocol("HTTPS").loadBalancerPort(443).instancePort(8443).build())
                                    .build()
                                )).build()
                    , Location(Profile("test"), Region.US_WEST_1), "Classic").apply {
                        instances = listOf(matchedInstances(id = "instanceone", dnsName = "instanceone.wds.co" ), matchedInstances(id = "instancetwo", dnsName = "instancetwo.wds.co")).flatten().toMutableList()
                    },
                    InstancedLoadBalancer(LoadBalancerDescription.builder()
                            .loadBalancerName("thewrongone")
                            .dnsName("thewrongone.amazon.com")
                            .listenerDescriptions(listOf(ListenerDescription.builder()
                                .listener(Listener.builder().protocol("HTTPS").loadBalancerPort(443).instancePort(8443).build())
                                .build()
                            ))
                            .build()
                    , Location(Profile("test"), Region.US_WEST_1), "Classic").apply {
                        instances = listOf(matchedInstances(id = "instancethree", dnsName = "instancethree.wds.co"), matchedInstances(id = "instancefour", dnsName = "instancefour.wds.co")).flatten().toMutableList()
                    }
            )
        }

        it("lists instances attached to the named load balancer") {
            val subject = APIController(mockRepo)

            val instances = subject.instancesForElb("therightone")

            assertThat(instances, equalTo("instancetwo.wds.co:8080\ninstanceone.wds.co:8080\n"))
        }

        it("returns nothing if elb name is empty") {
            val subject = APIController(mockRepo)

            val instances = subject.instancesForElb("")

            assertThat(instances, absent())
        }

        it("returns nothing if elb name is not found") {
            val subject = APIController(mockRepo)

            val instances = subject.instancesForElb("notthere")

            assertThat(instances, absent())
        }

        it("uses https if no http listener") {
            val subject = APIController(mockRepo)

            val instances = subject.instancesForElb("thewrongone")

            assertThat(instances, equalTo("instancefour.wds.co:8443\ninstancethree.wds.co:8443\n"))
        }
    }
})

fun tag(key: String, value: String): Tag = Tag.builder().key(key).value(value).build()