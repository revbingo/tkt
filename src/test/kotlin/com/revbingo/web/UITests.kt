package com.revbingo.web

import com.gargoylesoftware.htmlunit.WebClient
import com.gargoylesoftware.htmlunit.html.DomElement
import com.gargoylesoftware.htmlunit.html.DomNode
import com.gargoylesoftware.htmlunit.html.HtmlPage
import com.natpryce.hamkrest.*
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.should.shouldMatch
import com.nhaarman.mockitokotlin2.*

import com.revbingo.aws.*
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.subject.SubjectSpek
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudformation.model.Stack
import software.amazon.awssdk.services.ec2.model.Subnet
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.model.Volume
import software.amazon.awssdk.services.ec2.model.VolumeAttachment
import software.amazon.awssdk.services.elasticache.model.CacheCluster
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener
import software.amazon.awssdk.services.elasticloadbalancing.model.ListenerDescription
import software.amazon.awssdk.services.elasticloadbalancing.model.LoadBalancerDescription
import software.amazon.awssdk.services.rds.model.DBInstance
import software.amazon.awssdk.services.route53.model.ResourceRecord
import software.amazon.awssdk.services.route53.model.ResourceRecordSet

import java.time.LocalDateTime

class UITests : SubjectSpek<Application>({
    val theSubnet =  VPCSubnet(Subnet.builder()
            .subnetId("subnet-abcd")
            .vpcId("vpc-123")
            .tags(listOf(tag("Name", "mySubnet")))
            .cidrBlock("1.2.3.4/5")
            .availabilityZone("us-west-1a")
            .defaultForAz(false)
            .build()
            ,  Location(Profile("test"), Region.US_WEST_1))

    val mockRepo: Repository = mock {


        on { subnets } doReturn listOf(
               theSubnet
        )

        on { instances } doReturn listOf(
                matchedInstances(count = 1, type = "m3.large", privateIpAddress = "10.11.12.13", dnsName = "ec2-1-2-3-4", az = "us-west-1a",
                        id = "abc1", vpcId = "vpc", subnet = theSubnet, tags = listOf(tag("Name", "server1"), tag("Capability", "Management"),
                        tag("Client", "WDS"), tag("Environment", "Prod"))).apply {
                    this.first().price = 0.832f
                },
                matchedInstances(count = 1, az = "eu-west-1b", id = "def1", tags = listOf(tag("Name", "server2")), state = "stopped").apply {
                    this.first().price = 0.4f
                },
                matchedInstances(count = 1, az = "ap-southeastt-1a", id = "ghi1", tags = listOf(tag("Name", "server3"))).apply {
                    this.first().matched = true
                    this.first().price = 0.3f
                }
        ).flatten()
//
        on { reservedInstances } doReturn countedReservations(count = 3, type = "t2.large").apply {
            this.first().unmatchedCount = 1
            this.first().computeUnits = 4.0f
        }

        on { loadBalancers } doReturn listOf(
                InstancedLoadBalancer(LoadBalancerDescription.builder()
                        .loadBalancerName("theELB")
                        .dnsName("dns-name")
                        .listenerDescriptions(listOf(
                                ListenerDescription.builder()
                                    .listener(Listener.builder().protocol("HTTP").loadBalancerPort(80).instancePort(8080).build())
                                    .build()
                                , ListenerDescription.builder()
                                .listener(Listener.builder().protocol("HTTPS").loadBalancerPort(443).instancePort(8443).build())
                                .build()))
                        .build()
                , Location(Profile("test"), Region.US_WEST_1), "Classic")
        )

        on { databases } doReturn listOf(
                RDSInstance(DBInstance.builder()
                        .dbInstanceIdentifier("myDatabase")
                        .dbInstanceClass("db.t2.small")
                        .engine("MySQL")
                        .engineVersion("5.7.3")
                        .multiAZ(false)
                        .allocatedStorage(50)
                        .availabilityZone("us-west-1b")
                        .endpoint(software.amazon.awssdk.services.rds.model.Endpoint.builder()
                                        .address("myDatabase.cdcdqwde.compute.amazonaws.com")
                                        .port(3306)
                                        .build())
                        .build()
                , Location(Profile("test"), Region.US_WEST_1))
        )

        on { domainNames } doReturn listOf(
                DomainName(ResourceRecordSet.builder()
                    .name("my.domain.name")
                    .type("CNAME")
                    .ttl(600)
                    .resourceRecords(listOf(ResourceRecord.builder().value("somewhere.somewhere.com").build()))
                    .build()
                )
        )

        on { volumes } doReturn listOf(
                EBSVolume(Volume.builder()
                        .volumeType("standard")
                        .volumeId("1234abc")
                        .size(50)
                        .iops(30)
                        .encrypted(false)
                        .state("in-use")
                        .attachments(listOf(VolumeAttachment.builder().build()))
                        .tags(listOf(tag("Name", "aVolume")))
                        .build()
                , Location(Profile("test"), Region.US_WEST_1)).apply {
                    attachedInstances = matchedInstances(count = 1, az = "eu-west-1b", id = "def1", tags = listOf(tag("Name", "server2")), state = "stopped").apply {
                        this.first().price = 0.4f
                    }
                }
        )

        on { caches } doReturn listOf(
                Cache(CacheCluster.builder()
                    .cacheClusterId("clusterId")
                    .configurationEndpoint(software.amazon.awssdk.services.elasticache.model.Endpoint.builder()
                            .address("mycache.amazon.com")
                            .port(11211)
                            .build())

                    .cacheNodeType("cache.m1.small")
                    .engine("memcached")
                    .engineVersion("1.4.5")
                    .cacheClusterStatus("available")
                    .numCacheNodes(3)
                            .build()
                , Location(Profile("test"), Region.US_WEST_1))
        )

        on { checkResults} doReturn listOf(
                AdvisorResult(Check("an Id", "A check for something"), "eu-west-1", "EC2", "myServer", "This server is broken", "$100", "Green")
        )

        on { stacks } doReturn listOf(
                CFStack(Stack.builder()
                    .stackId("123stack")
                    .stackName("MyCFStack")
                    .stackStatus("CREATE_COMPLETE")
                    .build()
                , Location(Profile("test"), Region.US_WEST_1))
        )

        on { updateTime } doReturn LocalDateTime.now()

        on { inError } doReturn true
        on { errorMessage } doReturn "something went wrong"

    }

    subject {
        Application(UIController(mockRepo), APIController(mockRepo))
    }

    beforeGroup {
        subject.ignite()
    }

    afterGroup {
        subject.extinguish()
    }

    val webClient = WebClient()

    describe("the server list page") {

        var page: HtmlPage? = null
        var dataTable: DomElement? = null

        it("is mapped to /") {
            page = webClient.getPage("http://localhost:4567/")
            dataTable = page?.getElementById("data-table")
        }

        it("has a data table") {
            dataTable shouldBe present()
        }

        it("has a row for each server") {
            dataTable("tbody/tr") shouldHaveLength equalTo(3)

            val info = page("//div[@id='data-table_info']").firstOrNull()
            info shouldBe present()
            info?.asText() shouldBe equalTo("Showing 1 to 3 of 3 entries")
        }

        it("has the server name and server id") {
            page shouldContain "server1"
            page shouldContain "server2"
            page shouldContain "server3"

            page shouldContain "abc1"
            page shouldContain "def1"
            page shouldContain "ghi1"
        }

        it("has a bunch of info about a server") {
            val row1 = dataTable("tbody/tr[1]")[0]

            row1 shouldContain "server1||WDS|Management|Prod|abc1|m3.large|us-west-1|a|Linux/UNIX|ec2-1-2-3-4|10.11.12.13|mySubnet|running|test|false|0.832".tsv()
        }
    }

    describe("the ELB page") {
        var page: HtmlPage?
        var dataTable: DomElement? = null

        it("is mapped to /elb") {
            page = webClient.getPage("http://localhost:4567/elb")
            dataTable = page?.getElementById("data-table")
        }

        it("has a bunch of info about a load balancer") {
            val row1 = dataTable("tbody/tr[1]")[0]

            row1 shouldContain "theELB||dns-name|us-west-1|Classic|test|8080|8443|0".tsv()
        }
    }

    describe("the dashboard") {
        var page: HtmlPage?
        var statBoxes: List<DomNode?> = emptyList()

        it("is mapped to /dashboard") {
            page = webClient.getPage("http://localhost:4567/dashboard")
            statBoxes = page("//div[contains(@class,'statBox')]")

        }
        it("shows the total number of servers") {
            statBoxes[0] shouldContain "3\ninstances"
        }

        it("shows the number of running servers") {
            statBoxes[1] shouldContain "2\nrunning"
        }

        it("shows the number of matched reservations") {
            statBoxes[2] shouldContain "12.0\nreserved units"
        }

        it("shows the number of unmatched reservations") {
            statBoxes[3] shouldContain "4.0\nunmatched reserved units"
        }

        it("shows the number of servers in VPC") {
            statBoxes[4] shouldContain "1\nin VPC"
        }

        it("shows the cost of all running servers") {
            statBoxes[5] shouldContain "$1.13"
        }
    }

    describe("the update URL") {
        it("updates the repository") {
            //this is a little bit icky, necessary to make sure the test doesn't pass if update is ever
            //inadvertently called in another test
            verify(mockRepo, never()).update()

            webClient.getPage<HtmlPage>("http://localhost:4567/update")

            verify(mockRepo).update()
        }
    }

    describe("the RDS listing") {

        var page: HtmlPage?
        var dataTable: DomElement? = null

        it("is mapped to /rds") {
            page = webClient.getPage("http://localhost:4567/rds")
            dataTable = page?.getElementById("data-table")
        }

        it("contains a bunch of info about RDS instances") {
            dataTable("tbody/tr[1]")[0] shouldContain "myDatabase||myDatabase.cdcdqwde.compute.amazonaws.com:3306|us-west-1|db.t2.small|MySQL 5.7.3|false|50 Gb|test".tsv()
        }
    }

    describe("the DNS listing") {
        var page: HtmlPage?
        var dataTable: DomElement? = null

        it("is mapped to /route53") {
            page = webClient.getPage("http://localhost:4567/route53")
            dataTable = page?.getElementById("data-table")
        }

        it("contains a bunch of info about domain names") {
            dataTable.firstRow() shouldContain "my.domain.name||CNAME|somewhere.somewhere.com|600".tsv()
        }
    }

    describe("the EBS listing") {
        var page: HtmlPage?
        var dataTable: DomElement? = null

        it("is mapped to /volumes") {
            page = webClient.getPage("http://localhost:4567/volumes")
            dataTable = page?.getElementById("data-table")
        }

        it("contains a bunch of info about volumes") {
            dataTable.firstRow() shouldContain "aVolume|1234abc|us-west-1|in-use|standard|50|30|server2|test".tsv()
        }

        it("has a link to the instance") {
            assertThat(dataTable.firstRow().single("td[8]/a").attribute("href"), equalTo("/?id=def1"))
        }
    }

    describe("the cache listing") {
        var page: HtmlPage?
        var dataTable: DomElement? = null

        it("is mapped to /caches") {
            page = webClient.getPage("http://localhost:4567/caches")
            dataTable = page?.getElementById("data-table")
        }

        it("contains a bunch of info about caches") {
            dataTable.firstRow() shouldContain "clusterId|mycache.amazon.com:11211|us-west-1|cache.m1.small|memcached 1.4.5|available|3|test".tsv()
        }
    }

    describe("the subnet listing") {
        var page: HtmlPage?
        var dataTable: DomElement? = null

        it("is mapped to /subnets") {
            page = webClient.getPage("http://localhost:4567/subnets")
            dataTable = page?.getElementById("data-table")
        }

        it("contains a bunch of info about subnets") {
            dataTable.firstRow() shouldContain "subnet-abcd||mySubnet|vpc-123|test|us-west-1a|1.2.3.4/5|false".tsv()
        }
    }

    describe("the stack listing") {
        var page: HtmlPage?
        var dataTable: DomElement? = null

        it("is mapped to /stacks") {
            page = webClient.getPage("http://localhost:4567/stacks")
            dataTable = page?.getElementById("data-table")
        }

        it("contains a bunch of info about subnets") {
            dataTable.firstRow() shouldContain "MyCFStack|test|us-west-1".tsv()
        }
    }

    describe("the checks listing") {
        var page: HtmlPage?
        var dataTable: DomElement? = null

        it("is mapped to /checks") {
            page = webClient.getPage("http://localhost:4567/checks")
            dataTable = page?.getElementById("data-table")
        }

        it("contains a bunch of info about checks") {
            dataTable.firstRow() shouldContain "A check for something|EC2|myServer|eu-west-1|This server is broken|$100|Green".tsv()
        }
    }

    describe("any page") {
        var page: HtmlPage?

        it("shows an error message if one occurred") {
            page = webClient.getPage("http://localhost:4567/")
            val errorDiv = page?.getElementById("error")

            errorDiv shouldContain "Error in repository update: something went wrong"
        }
    }
})

infix fun <T> T.shouldBe(matcher: Matcher<T>) = shouldMatch(matcher)

infix fun DomNode?.shouldContain(string: String) {
    this ?: throw NullPointerException()
    this.asText().shouldMatch(containsSubstring(string))
}

infix fun <T: Any> Collection<T>.shouldHaveLength(matcher: Matcher<Int>) = assertThat(this, hasSize(matcher))

operator fun <T: DomNode> T?.invoke(xpath: String): List<DomNode> {
    this ?: throw NullPointerException()
    return this.getByXPath(xpath)
}

fun DomNode?.single(xpath: String): DomNode {
    val nodes = this!!.getByXPath<DomNode>(xpath)
    if(nodes.size != 1) throw IllegalStateException("expected a single node")
    return nodes[0]
}

fun DomNode?.firstRow(): DomNode = this.single("tbody/tr[1]")

fun DomNode?.attribute(name: String): String? = this?.attributes?.getNamedItem(name)?.nodeValue
fun String.tsv(): String = this.replace("|","\t")