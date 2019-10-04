package com.revbingo.aws

import com.natpryce.hamkrest.absent
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.xit
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.model.*
import software.amazon.awssdk.services.elasticache.model.CacheCluster
import software.amazon.awssdk.services.rds.model.DBInstance
import software.amazon.awssdk.services.rds.model.Endpoint
import software.amazon.awssdk.services.support.model.DescribeTrustedAdvisorCheckResultResponse
import software.amazon.awssdk.services.support.model.TrustedAdvisorCheckResult
import software.amazon.awssdk.services.support.model.TrustedAdvisorResourceDetail
import java.util.*
import kotlin.text.isNullOrBlank

class ModelsTest : Spek({

    val testAccount = Location(Profile("Test"), Region.EU_WEST_1)
    
    describe("A CountedReservation") {
        it("delegates some properties to the original") {
            var original = reservedInstance(state = "active", az = "us-west-1a", count = 10, type = "m3.large")
            original = original.toBuilder()
                    .scope("scope")
                    .end(Date(12345L).toInstant())
                    .reservedInstancesId("theId")
                    .build()
            val unit = CountedReservation(original, testAccount)

            assertThat(unit.state, equalTo("active"))
            assertThat(unit.availabilityZone, equalTo("us-west-1a"))
            assertThat(unit.scope, equalTo("scope"))
            assertThat(unit.instanceCount, equalTo(10))
            assertThat(unit.instanceType, equalTo("m3.large"))
            assertThat(unit.family, equalTo("m3"))
            assertThat(unit.instanceSize, equalTo("large"))
            assertThat(unit.computeUnits, equalTo(40f))
            assertThat(unit.end, equalTo(Date(12345L)))
            assertThat(unit.id, equalTo("theId"))
        }

        it("calculates compute units based on size of instances") {
            var original = reservedInstance(state = "active", az = "us-west-1a", count = 1, type = "t2.micro")
            var unit = CountedReservation(original, testAccount)

            assertThat(unit.computeUnits, equalTo(0.5f))

            original = reservedInstance(state = "active", az = "us-west-1a", count = 1, type = "t2.small")
            unit = CountedReservation(original, testAccount)

            assertThat(unit.computeUnits, equalTo(1f))

            original = reservedInstance(state = "active", az = "us-west-1a", count = 1, type = "t2.medium")
            unit = CountedReservation(original, testAccount)

            assertThat(unit.computeUnits, equalTo(2f))

            original = reservedInstance(state = "active", az = "us-west-1a", count = 1, type = "t2.large")
            unit = CountedReservation(original, testAccount)

            assertThat(unit.computeUnits, equalTo(4f))

            original = reservedInstance(state = "active", az = "us-west-1a", count = 1, type = "t2.xlarge")
            unit = CountedReservation(original, testAccount)

            assertThat(unit.computeUnits, equalTo(8f))

            original = reservedInstance(state = "active", az = "us-west-1a", count = 1, type = "t2.2xlarge")
            unit = CountedReservation(original, testAccount)

            assertThat(unit.computeUnits, equalTo(16f))
        }

        it("is marked as active if the state is active") {
            val original = reservedInstance(state = "active")

            val unit = CountedReservation(original, testAccount)

            assertThat(unit.isActive, equalTo(true))
        }

        it("is marked as not active if the state is retired") {
            val original = reservedInstance(state = "retired")

            val unit = CountedReservation(original, testAccount)

            assertThat(unit.isActive, equalTo(false))
        }

        it("reports a matchedCount that is the original count minus unmatched") {
            val original = reservedInstance(state = "active", count = 10)

            val unit = CountedReservation(original, testAccount)
            unit.unmatchedCount--
            unit.unmatchedCount--
            unit.unmatchedCount--

            assertThat(unit.matchedCount(), equalTo(3))
        }
    }

    describe("A MatchedInstance") {
        it("delegates some properties to the original") {
            val original = instance(state = "running", az = "us-west-1b", type = "m3.large",
                    vpcId = "vpc123", id = "instanceId", dnsName = "ec2-12-34-56-78.compute-1.amazonaws.com",
                    publicIpAddress = "1.2.3.4", privateIpAddress = "100.99.98.97")

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.state, equalTo(InstanceState.builder().name("running").build()))
            assertThat(unit.availabilityZone, equalTo("us-west-1b"))
            assertThat(unit.instanceType, equalTo("m3.large"))
            assertThat(unit.family, equalTo("m3"))
            assertThat(unit.instanceSize, equalTo("large"))
            assertThat(unit.vpcId, equalTo("vpc123"))
            assertThat(unit.instanceId, equalTo("instanceId"))
            assertThat(unit.publicDnsName, equalTo("ec2-12-34-56-78.compute-1.amazonaws.com"))
            assertThat(unit.publicIpAddress, equalTo("1.2.3.4"))
            assertThat(unit.privateIpAddress, equalTo("100.99.98.97"))
        }

        it("normalises platform 'windows' to 'Windows'") {
            val original = instance(platform = "windows")

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.platform, equalTo("Windows"))
        }

        it("sets platform to Linux/UNIX if not specified in the underlying instance") {
            val original = instance()

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.platform, equalTo("Linux/UNIX"))
        }

        it("marks as inVPC if vpcId is specified") {
            val original = instance(vpcId = "vpc123")

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.isVpc, equalTo(true))
        }

        it("marks as not inVPC if no vpcId is specified") {
            val original = instance()

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.isVpc, equalTo(false))
        }

        it("marks as not inVPC if no vpcId is empty") {
            val original = instance(vpcId = "")

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.isVpc, equalTo(false))
        }

        it("formats launchDate as ISO_DATE") {
            //this is probably a flaky test if you're not GMT
            val original = instance(launchTime = Date(1234567890123L).toInstant())

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.launchDate, equalTo("2009-02-13"))
        }

        it("strips DNS name down to just IP part") {
            val original = instance(dnsName = "ec2-12-34-56-78.compute-1.amazonaws.com")

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.shortDnsName, equalTo("ec2-12-34-56-78"))
        }

        it("gets name from the Name tag") {
            val original = instance(tags = listOf(Tag.builder().key("Name").value("bob").build()))

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.name, equalTo("bob"))
        }

        it("gets client from the Client tag") {
            val original = instance(tags = listOf(Tag.builder().key("Client").value("customer").build()))

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.client, equalTo("customer"))
        }

        it("gets capability from the Capability tag") {
            val original = instance(tags = listOf(Tag.builder().key("Capability").value("Brown").build()))

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.capability, equalTo("Brown"))
        }

        it("gets environment from the Environment tag") {
            val original = instance(tags = listOf(Tag.builder().key("Environment").value("prod").build()))

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.environment, equalTo("prod"))
        }

        it("can get an arbitrary tag") {
            val original = instance(tags = listOf(
                                                Tag.builder().key("One").value("1").build(),
                                                Tag.builder().key("Two").value("2").build())
                                            )

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.tag("One"), equalTo("1"))
            assertThat(unit.tag("Two"), equalTo("2"))
        }

        it("doesn't blow up if tags are not present") {
            var original = instance()
            original = original.toBuilder().tags(Tag.builder().key("AnotherTag").value("SomethingElse").build()).build()

            val unit = MatchedInstance(original, testAccount)

            assertThat(unit.name, String?::isNullOrBlank)
            assertThat(unit.client, String?::isNullOrBlank)
            assertThat(unit.capability, String?::isNullOrBlank)
        }

        it("matches availablility zone if they're the same") {
            val reservation = CountedReservation(reservedInstance(az = "us-west-1b"), testAccount)
            val instanceB = MatchedInstance(instance(az = "us-west-1b"), testAccount)
            val instanceD = MatchedInstance(instance(az = "us-west-1D"), testAccount)

            assertThat(instanceB.sameZoneAs(reservation), equalTo(true))
            assertThat(instanceD.sameZoneAs(reservation), equalTo(false))
        }

        it("matches availability zone if regionScope is set and the region is the same") {
            val reservation = CountedReservation(reservedInstance(regionScope = true), Location(Profile(""), Region.US_WEST_1))
            val instanceB = MatchedInstance(instance(az = "us-west-1b"), Location(Profile(""), Region.US_WEST_1))
            val instanceD = MatchedInstance(instance(az = "us-west-1d"), Location(Profile(""), Region.US_WEST_1))
            val instanceEU = MatchedInstance(instance(az = "eu-west-1d"), Location(Profile(""), Region.EU_WEST_1))

            assertThat(instanceB.sameZoneAs(reservation), equalTo(true))
            assertThat(instanceD.sameZoneAs(reservation), equalTo(true))
            assertThat(instanceEU.sameZoneAs(reservation), equalTo(false))
        }

        it("has a region without AZ code") {
            val instanceEU = MatchedInstance(instance(az="eu-west-1b"), testAccount)
            val instanceUSW1 = MatchedInstance(instance(az="us-west-1a"), testAccount)
            val instanceUSW2 = MatchedInstance(instance(az="us-west-2b"), testAccount)
            val instanceUSE = MatchedInstance(instance(az="us-east-1c"), testAccount)
            val instanceAPS = MatchedInstance(instance(az="ap-southeast-2c"), testAccount)

            assertThat(instanceEU.region, equalTo("eu-west-1"))
            assertThat(instanceUSW1.region, equalTo("us-west-1"))
            assertThat(instanceUSW2.region, equalTo("us-west-2"))
            assertThat(instanceUSE.region, equalTo("us-east-1"))
            assertThat(instanceAPS.region, equalTo("ap-southeast-2"))
        }

        it("has an AZ code") {
            val instanceEU = MatchedInstance(instance(az="eu-west-1b"), testAccount)
            val instanceUSW1 = MatchedInstance(instance(az="us-west-1a"), testAccount)
            val instanceUSW2 = MatchedInstance(instance(az="us-west-2b"), testAccount)
            val instanceUSE = MatchedInstance(instance(az="us-east-1c"), testAccount)
            val instanceAPS = MatchedInstance(instance(az="ap-southeast-2c"), testAccount)

            assertThat(instanceEU.zone, equalTo("b"))
            assertThat(instanceUSW1.zone, equalTo("a"))
            assertThat(instanceUSW2.zone, equalTo("b"))
            assertThat(instanceUSE.zone, equalTo("c"))
            assertThat(instanceAPS.zone, equalTo("c"))
        }

        it("matches instance type if they're exactly the same and scope is not Region") {
            val reservation = CountedReservation(reservedInstance(type = "m3.large", regionScope = false), testAccount)
            val instanceLarge = MatchedInstance(instance(type = "m3.large"), testAccount)
            val instanceSmall = MatchedInstance(instance(type = "m3.small"), testAccount)

            assertThat(instanceLarge.sameTypeAs(reservation), equalTo(true))
            assertThat(instanceSmall.sameTypeAs(reservation), equalTo(false))
        }

        it("matches instance type if they're the same family and scope is Region") {
            val reservation = CountedReservation(reservedInstance(type = "m3.large", regionScope = true), testAccount)
            val instanceLarge = MatchedInstance(instance(type = "m3.large"), testAccount)
            val instanceSmall = MatchedInstance(instance(type = "m3.small"), testAccount)

            assertThat(instanceLarge.sameTypeAs(reservation), equalTo(true))
            assertThat(instanceSmall.sameTypeAs(reservation), equalTo(true))
        }

        it("matches product type if they're the same, regardless of case") {
            val reservation = CountedReservation(reservedInstance(product = "Windows"), testAccount)
            val instanceWindows= MatchedInstance(instance(platform = "windows"), testAccount)
            val instanceLinux = MatchedInstance(instance(platform = "Linux/UNIX"), testAccount)

            assertThat(instanceWindows.sameProductAs(reservation), equalTo(true))
            assertThat(instanceLinux.sameProductAs(reservation), equalTo(false))
        }

        it("matches overall if zone, type, product are the same") {
            val reservation = CountedReservation(reservedInstance(product = "Linux/UNIX", type = "m3.large", az = "us-west-1b"), testAccount)
            val matchingInstance = MatchedInstance(instance(platform = "Linux/UNIX", vpcId = "vpc123", type = "m3.large", az = "us-west-1b"), testAccount)
            val instanceDifferentZone = MatchedInstance(instance(platform = "Linux/UNIX", vpcId = "vpc123", type = "m3.large", az = "us-west-1c"), testAccount)
            val instanceDifferentProduct = MatchedInstance(instance(platform = "Windows", vpcId = "vpc123", type = "m3.large", az = "us-west-1b"), testAccount)
            val instanceDifferentType = MatchedInstance(instance(platform = "Linux/UNIX", vpcId = "vpc123", type = "m3.small", az = "us-west-1b"), testAccount)

            assertThat(matchingInstance.matches(reservation), equalTo(true))
            assertThat(instanceDifferentZone.matches(reservation), equalTo(false))
            assertThat(instanceDifferentProduct.matches(reservation), equalTo(false))
            assertThat(instanceDifferentType.matches(reservation), equalTo(false))
        }
    }

    describe("An RDS instance") {
        it("delegates properties to the original") {
            val originalInstance = DBInstance.builder()
                    .dbInstanceIdentifier("myDatabase")
                    .dbInstanceClass("db.t2.small")
                    .engine("MySQL")
                    .engineVersion("5.7.3")
                    .multiAZ(false)
                    .allocatedStorage(50)
                    .availabilityZone("us-west-1b")
                    .endpoint(Endpoint.builder()
                            .address("myDatabase.cdcdqwde.compute.amazonaws.com")
                            .port(3306)
                            .build()
                    )
                    .build()

            val subject = RDSInstance(originalInstance, Location(Profile("test"), Region.US_WEST_1))

            assertThat(subject.name, equalTo("myDatabase"))
            assertThat(subject.type, equalTo("db.t2.small"))
            assertThat(subject.multiAZ, equalTo(false))
            assertThat(subject.storage, equalTo(50))
            assertThat(subject.engine, equalTo("MySQL"))
            assertThat(subject.engineVersion, equalTo("5.7.3"))
            assertThat(subject.region, equalTo("us-west-1"))
            assertThat(subject.endpoint, equalTo("myDatabase.cdcdqwde.compute.amazonaws.com:3306"))
        }

        it("has an id that is the name") {
            val originalInstance = DBInstance.builder()
                    .dbInstanceIdentifier("myDatabase")
                    .dbInstanceClass("db.t2.small")
                    .engine("MySQL")
                    .engineVersion("5.7.3")
                    .multiAZ(false)
                    .allocatedStorage(50)
                    .availabilityZone("us-west-1b")
                    .endpoint(Endpoint.builder()
                        .address("myDatabase.cdcdqwde.compute.amazonaws.com")
                        .port(3306)
                        .build()
                    )
                    .build()

            val subject = RDSInstance(originalInstance, Location(Profile("test"), Region.US_WEST_1))

            assertThat(subject.id, equalTo(subject.name))
        }

        it("doesn't need an endpoint if the instance is starting") {
            val originalInstance = DBInstance.builder()
                    .dbInstanceIdentifier("myDatabase")
                    .dbInstanceClass("db.t2.small")
                    .engine("MySQL")
                    .engineVersion("5.7.3")
                    .multiAZ(false)
                    .allocatedStorage(50)
                    .availabilityZone("us-west-1b")
                    .build()

            val subject = RDSInstance(originalInstance, Location(Profile("test"), Region.US_WEST_1))
            assertThat(subject.endpoint, equalTo(""))
        }
    }

    describe("an EBS volume") {
        it("delegates properties to the original") {
            val originalInstance = Volume.builder()
                    .volumeType("standard")
                    .volumeId("1234abc")
                    .size(50)
                    .iops(30)
                    .encrypted(false)
                    .state("in-use")
                    .attachments(listOf(VolumeAttachment.builder().instanceId("77777").build()))
                    .tags(listOf(Tag.builder().key("Name").value("aVolume").build()))
                    .build()

            val subject = EBSVolume(originalInstance, Location(Profile("test"), Region.US_WEST_1))

            assertThat(subject.name, equalTo("aVolume"))
            assertThat(subject.type, equalTo("standard"))
            assertThat(subject.size, equalTo(50))
            assertThat(subject.iops, equalTo(30))
            assertThat(subject.encrypted, equalTo(false))
            assertThat(subject.state, equalTo("in-use"))
            assertThat(subject.attachedInstanceIds[0], equalTo("77777"))
            assertThat(subject.id, equalTo("1234abc"))
        }

        it("doesn't blow up if Name tag not present") {
            val originalInstance = Volume.builder()
                    .volumeType("standard")
                    .volumeId("1234abc")
                    .size(50)
                    .iops(30)
                    .encrypted(false)
                    .state("in-use")
                    .attachments(listOf(VolumeAttachment.builder().instanceId("77777").build()))
                    .build()

            val subject = EBSVolume(originalInstance, Location(Profile("test"), Region.US_WEST_1))
            assertThat(subject.name, absent())
        }

        it("doesn't blow up if instances not attached") {
            val originalInstance = Volume.builder()
                    .volumeType("standard")
                    .volumeId("1234abc")
                    .size(50)
                    .iops(30)
                    .encrypted(false)
                    .state("in-use")
                    .build()

            val subject = EBSVolume(originalInstance, Location(Profile("test"), Region.US_WEST_1))
            assertThat(subject.attachedInstanceIds, equalTo(emptyList()))
        }
    }

    describe("A cache") {
        it("delegates properties to the original") {
            val originalInstance = CacheCluster.builder()
                .cacheClusterId("clusterId")
                .configurationEndpoint(software.amazon.awssdk.services.elasticache.model.Endpoint.builder()
                    .address("mycache.amazon.com")
                    .port(11211)
                    .build()
                )
                .cacheNodeType("cache.m1.small")
                .engine("memcached")
                .engineVersion("1.4.5")
                .cacheClusterStatus("available")
                .numCacheNodes(3)
                .build()

            val subject = Cache(originalInstance, Location(Profile("test"), Region.US_WEST_1))

            assertThat(subject.id, equalTo("clusterId"))
            assertThat(subject.endpoint, equalTo("mycache.amazon.com:11211"))
            assertThat(subject.type, equalTo("cache.m1.small"))
            assertThat(subject.engine, equalTo("memcached"))
            assertThat(subject.engineVersion, equalTo("1.4.5"))
            assertThat(subject.status, equalTo("available"))
            assertThat(subject.nodeCount, equalTo(3))
        }
    }

    describe("A subnet") {
        it("gets name from the tags") {
            val originalInstance = Subnet.builder()
                .subnetId("subnet-abcd")
                .vpcId("vpc-123")
                .tags(listOf(Tag.builder().key("Name").value("mySubnet").build()))
                .cidrBlock("1.2.3.4/5")
                .availabilityZone("us-west-1a")
                .defaultForAz(false)
                .build()

            val subject = VPCSubnet(originalInstance, Location(Profile("test"), Region.US_WEST_1))

            assertThat(subject.name, equalTo("mySubnet"))
        }
    }

    describe("a spot request") {
        it("gets id and from the spot request") {
            val originalInstance = SpotInstanceRequest.builder()
                .spotInstanceRequestId("instance-request")
                .spotPrice("0.01")
                .actualBlockHourlyPrice("0.002")
                .build()

            val subject = SpotRequest(originalInstance)

            assertThat(subject.id, equalTo("instance-request"))
            assertThat(subject.price, equalTo(0.002f))
        }

        it("gets price as the spotPrice if hourly is not available") {
            val originalInstance = SpotInstanceRequest.builder()
                    .spotInstanceRequestId("instance-request")
                    .spotPrice("0.01")
                    .build()

            val subject = SpotRequest(originalInstance)

            assertThat(subject.id, equalTo("instance-request"))
            assertThat(subject.price, equalTo(0.01f))
        }
    }

    describe("Advisor results") {
        it("maps low utiliisation instances") {
            val result = AdvisorResult.create(check("Low Utilization Amazon EC2 Instances"), result("[eu-west-1a, i-095fb8155d58215ae, mongo-uat-01, t2.micro, $9.36, null, null, null, null, 0.9%  1.05MB, 0.7%  0.03MB, 0.8%  0.20MB, 0.5%  0.03MB, 0.4%  0.00MB, 0.4%  0.00MB, 0.4%  0.00MB, 0.4%  0.00MB, 0.4%  0.00MB, 0.4%  0.00MB, 0.5%, 0.13MB, 10 days]"))
            with(result[0]) {
                assertThat(check.name, equalTo("Low Utilization Amazon EC2 Instances"))
                assertThat(rating, equalTo("None"))
                assertThat(saving, equalTo("$9.36"))
                assertThat(region, equalTo("eu-west-1a"))
                assertThat(resourceType, equalTo("EC2"))
                assertThat(resourceId, equalTo("i-095fb8155d58215ae"))
                assertThat(description, equalTo("mongo-uat-01 is a t2.micro"))
            }
        }

        it("maps idle load balancers") {
            val result = AdvisorResult.create(check("Idle Load Balancers"), result("[eu-west-1, mindmeld-telkom-uat, Low request count, $20.16]"))
            with(result[0]) {
                assertThat(check.name, equalTo("Idle Load Balancers"))
                assertThat(rating, equalTo("None"))
                assertThat(saving, equalTo("$20.16"))
                assertThat(region, equalTo("eu-west-1"))
                assertThat(resourceType, equalTo("ELB"))
                assertThat(resourceId, equalTo("mindmeld-telkom-uat"))
                assertThat(description, equalTo("Low request count"))
            }
        }

        it("maps underutilised EBS volumes") {
            val result = AdvisorResult.create(check("Underutilized Amazon EBS Volumes"), result("[eu-west-1, vol-fc164094, moss-live, Magnetic, 100, $5.50, null, null, null]"))
            with(result[0]) {
                assertThat(check.name, equalTo("Underutilized Amazon EBS Volumes"))
                assertThat(rating, equalTo("None"))
                assertThat(saving, equalTo("$5.50"))
                assertThat(region, equalTo("eu-west-1"))
                assertThat(resourceType, equalTo("EBS"))
                assertThat(resourceId, equalTo("vol-fc164094"))
                assertThat(description, equalTo("Volume moss-live, 100Gb Magnetic"))
            }
        }

        it("maps unused IP addresses") {
            val result = AdvisorResult.create(check("Unassociated Elastic IP Addresses"), result("[eu-west-1, 54.75.234.25]"))
            with(result[0]) {
                assertThat(check.name, equalTo("Unassociated Elastic IP Addresses"))
                assertThat(rating, equalTo("None"))
                assertThat(saving, equalTo(""))
                assertThat(region, equalTo("eu-west-1"))
                assertThat(resourceType, equalTo("EIP"))
                assertThat(resourceId, equalTo("54.75.234.25"))
                assertThat(description, equalTo("IP is unused"))
            }
        }

        it("maps unsecured security groups (specific ports)") {
            val result = AdvisorResult.create(check("Security Groups - Specific Ports Unrestricted"), result("[eu-west-1, add-mz, sg-ae5724d9, tcp, Yellow, 8080]"))
            with(result[0]) {
                assertThat(rating, equalTo("Yellow"))
                assertThat(saving, equalTo(""))
                assertThat(region, equalTo("eu-west-1"))
                assertThat(resourceType, equalTo("EC2"))
                assertThat(resourceId, equalTo("add-mz"))
                assertThat(description, equalTo("Port 8080 (tcp) is open"))
            }
        }

        it("maps unsecured security groups (unrestricted access)") {
            val result = AdvisorResult.create(check("Security Groups - Unrestricted Access"), result("[eu-west-1, add-mz, sg-ae5724d9, tcp, 8080, Red, 0.0.0.0/0]"))
            with(result[0]) {
                assertThat(rating, equalTo("Red"))
                assertThat(saving, equalTo(""))
                assertThat(region, equalTo("eu-west-1"))
                assertThat(resourceType, equalTo("EC2"))
                assertThat(resourceId, equalTo("add-mz"))
                assertThat(description, equalTo("Port 8080 (tcp) is open to 0.0.0.0/0"))
            }
        }

        it("maps unsecured S3 buckets") {
            val result = AdvisorResult.create(check("Amazon S3 Bucket Permissions"), result("[us-west-1, us-west-1, globalmine-image-library, Yes, No, Yellow, null]"))
            with(result[0]) {
                assertThat(rating, equalTo("Yellow"))
                assertThat(saving, equalTo(""))
                assertThat(region, equalTo("us-west-1"))
                assertThat(resourceType, equalTo("S3"))
                assertThat(resourceId, equalTo("globalmine-image-library"))
                assertThat(description, equalTo("Bucket has global permissions (List: Yes, Upload/Delete: No)"))
            }
        }

        it("maps RDS security group risks") {
            val result = AdvisorResult.create(check("Amazon RDS Security Group Access Risk"), result("[eu-west-1, default, default-ingress, Yellow, Amazon EC2 security group global access]"))
            with(result[0]) {
                assertThat(rating, equalTo("Yellow"))
                assertThat(saving, equalTo(""))
                assertThat(region, equalTo("eu-west-1"))
                assertThat(resourceType, equalTo("RDS"))
                assertThat(resourceId, equalTo("default"))
                assertThat(description, equalTo("Ingress default-ingress: Amazon EC2 security group global access"))
            }
        }

        it("maps unsnapshotted EBS") {
            val result = AdvisorResult.create(check("Amazon EBS Snapshots"), result("[eu-west-1, vol-d1bf1457, player2-eu-west-prod-02, null, null, null, /dev/xvda:i-bff5077f, Red, No snapshot]"))
            with(result[0]) {
                assertThat(rating, equalTo("Red"))
                assertThat(saving, equalTo(""))
                assertThat(region, equalTo("eu-west-1"))
                assertThat(resourceType, equalTo("EBS"))
                assertThat(resourceId, equalTo("vol-d1bf1457"))
                assertThat(description, equalTo("Volume player2-eu-west-prod-02 does not have a snapshot"))
            }
        }

        it("maps EBS with old snapshot") {
            val result = AdvisorResult.create(check("Amazon EBS Snapshots"), result("[eu-west-1, vol-5653d4a6, vamt-prod-01, snap-74ac105e, null, 580, /dev/xvda:i-d03db071, Red, Age]"))
            with(result[0]) {
                assertThat(rating, equalTo("Red"))
                assertThat(saving, equalTo(""))
                assertThat(region, equalTo("eu-west-1"))
                assertThat(resourceType, equalTo("EBS"))
                assertThat(resourceId, equalTo("vol-5653d4a6"))
                assertThat(description, equalTo("Volume vamt-prod-01 has a snapshot that is 580 days old"))
            }
        }

        it("maps AZ balance") {
            val result = AdvisorResult.create(check("Amazon EC2 Availability Zone Balance"), result("[eu-west-1, 42, 23, 19, 0, 0, Yellow, Uneven distribution]"))
            with(result[0]) {
                assertThat(rating, equalTo("Yellow"))
                assertThat(saving, equalTo(""))
                assertThat(region, equalTo("eu-west-1"))
                assertThat(resourceType, equalTo("-"))
                assertThat(resourceId, equalTo("-"))
                assertThat(description, equalTo("Region has an uneven balance of servers a:42, b:23, c:19, d:0, e:0"))
            }
        }

        it("maps Load Balance Optimization") {
            val result = AdvisorResult.create(check("Load Balancer Optimization "), result("[eu-west-1, moss, 1, 0, null, 1, null, null, Yellow, Single AZ]"))
            with(result[0]) {
                assertThat(rating, equalTo("Yellow"))
                assertThat(saving, equalTo(""))
                assertThat(region, equalTo("eu-west-1"))
                assertThat(resourceType, equalTo("ELB"))
                assertThat(resourceId, equalTo("moss"))
                assertThat(description, equalTo("Single AZ"))
            }
        }

        it("maps Load Balance Optimization if description is null") {
            val result = AdvisorResult.create(check("Load Balancer Optimization "), result("[eu-west-1, moss, 1, 0, null, 1, null, null, Yellow, null]"))
            with(result[0]) {
                assertThat(rating, equalTo("Yellow"))
                assertThat(saving, equalTo(""))
                assertThat(region, equalTo("eu-west-1"))
                assertThat(resourceType, equalTo("ELB"))
                assertThat(resourceId, equalTo("moss"))
                assertThat(description, equalTo(""))
            }
        }

        xit("maps VPN tunnel redundancy") {
            val result = AdvisorResult.create(check("VPN Tunnel Redundancy"), result("[eu-west-1, vpn-0130414a, vpc-9c68f6f8, vgw-baead9ce, cgw-b97b49cd, 1, Yellow, Check configuration]"))
            with(result[0]) {
                assertThat(rating, equalTo("Yellow"))
                assertThat(saving, equalTo(""))
                assertThat(region, equalTo("eu-west-1"))
                assertThat(resourceType, equalTo("VPC"))
                assertThat(resourceId, equalTo("vpn-0130414a"))
                assertThat(description, equalTo("VPN has less than two active tunnels. Check configuration"))
            }
        }

        it("maps multi-AZ RDS") {
            val result = AdvisorResult.create(check("Amazon RDS Multi-AZ"), result("[eu-west-1b, centralized-uat, vpc-9c68f6f8, false, Yellow]"))
            with(result[0]) {
                assertThat(rating, equalTo("Yellow"))
                assertThat(saving, equalTo(""))
                assertThat(region, equalTo("eu-west-1b"))
                assertThat(resourceType, equalTo("RDS"))
                assertThat(resourceId, equalTo("centralized-uat"))
                assertThat(description, equalTo("RDS instance centralized-uat is in a single AZ"))
            }
        }

        it("returns an empty result if an exception occurs") {
            val result = AdvisorResult.create(check("Amazon RDS Multi-AZ"), result("[eu-west-1b, null, null, null, null]"))
            with(result[0]) {
                assertThat(rating, equalTo("None"))
                assertThat(saving, equalTo(""))
                assertThat(region, equalTo("eu-west-1b"))
                assertThat(resourceType, equalTo("Unknown"))
                assertThat(resourceId, equalTo("unknown resource"))
                assertThat(description, equalTo("Exception mapping [eu-west-1b, null, null, null, null] for check 'Amazon RDS Multi-AZ': meta[4] must not be null"))
            }
        }
    }

})

fun check(name: String): Check {
    return Check("id", name)
}

fun result(metaAsString: String): DescribeTrustedAdvisorCheckResultResponse {
    val meta = parseMeta(metaAsString)
    return DescribeTrustedAdvisorCheckResultResponse.builder()
            .result(TrustedAdvisorCheckResult.builder()
                    .flaggedResources(listOf(
                            TrustedAdvisorResourceDetail.builder()
                                    .metadata(meta)
                                    .build())
                    )
                    .build())
            .build()
}

fun parseMeta(metaAsString: String): List<String?> {
    val stripped = metaAsString.subSequence(1,metaAsString.length - 1)
    return stripped.split(",").map { if(it.trim() == "null") null else it.trim() }
}