package com.revbingo.aws

import com.amazonaws.services.ec2.model.*
import com.amazonaws.services.elasticache.model.CacheCluster
import com.amazonaws.services.elasticloadbalancing.model.Listener
import com.amazonaws.services.elasticloadbalancing.model.ListenerDescription
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription
import com.amazonaws.services.rds.model.DBInstance
import com.amazonaws.services.route53.model.ResourceRecordSet
import com.amazonaws.services.support.model.DescribeTrustedAdvisorCheckResultResult
import com.revbingo.web.logger
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

interface AWSResource {
    val id: String
    var price: Float
}

fun computeUnitsFor(instanceSize: String) = when(instanceSize) {
    "micro" -> 0.5f
    "small" -> 1f
    "medium" -> 2f
    "large" -> 4f
    "xlarge" -> 8f
    "2xlarge" -> 16f
    else -> 0f
}

data class CountedReservation(val originalReservation: ReservedInstances, val location: Location, var unmatchedCount: Int = originalReservation.instanceCount): AWSResource {
    val state: String = originalReservation.state
    val isActive: Boolean = state == "active"
    val availabilityZone: String? = originalReservation.availabilityZone
    val scope: String? = originalReservation.scope
    val instanceType: String = originalReservation.instanceType
    val family: String = originalReservation.instanceType.split(".")[0]
    val instanceSize: String = originalReservation.instanceType.split(".")[1]
    val capacity: Float = originalReservation.instanceCount * computeUnitsFor(instanceSize)

    var computeUnits: Float = unmatchedCount * computeUnitsFor(instanceSize)

    val productDescription: String = originalReservation.productDescription
    val end: Date? = originalReservation.end
    val instanceCount: Int = originalReservation.instanceCount
    fun matchedCount(): Int = instanceCount - unmatchedCount


    fun usedCapacity(): Float = capacity - computeUnits

    fun unusedCapacity(): Float = computeUnits

    fun match(instance: MatchedInstance) {
        computeUnits -= instance.computeUnits
    }

    override val id: String = originalReservation.reservedInstancesId
    override var price: Float = 0.0f
}

data class MatchedInstance(val originalInstance: Instance, val location: Location, var matched: Boolean = false): AWSResource {
    val state: InstanceState = originalInstance.state
    val isRunning: Boolean = this.state.name == "running"
    val availabilityZone: String = originalInstance.placement.availabilityZone
    val instanceType: String = originalInstance.instanceType
    val family: String = originalInstance.instanceType.split(".")[0]
    val instanceSize: String = originalInstance.instanceType.split(".")[1]
    val computeUnits: Float = computeUnitsFor(instanceSize)
    val platform: String = if(originalInstance.platform.equals("windows", ignoreCase = true)) "Windows" else "Linux/UNIX"
    val vpcId: String? = originalInstance.vpcId
    val isVpc: Boolean = !vpcId.isNullOrEmpty()
    val launchDate: String? = DateTimeFormatter.ISO_DATE.format(originalInstance.launchTime.toInstant().atZone(ZoneId.of("UTC")).toLocalDateTime())
    val instanceId: String = originalInstance.instanceId
    val publicDnsName: String? = originalInstance.publicDnsName
    val publicIpAddress: String? = originalInstance.publicIpAddress
    val privateIpAddress: String? = originalInstance.privateIpAddress
    val shortDnsName: String? = originalInstance.publicDnsName?.substringBefore(".")
    val name: String? = tag("Name")
    val client: String? = tag("Client")
    val capability: String? = tag("Capability")
    val environment: String? = tag("Environment")
    val region: String? = availabilityZone.dropLast(1)
    val zone: String? = availabilityZone.takeLast(1)

    override val id: String = instanceId
    override var price: Float = 0.0f

    var advisoryNotices = mutableListOf<AdvisorResult>()

    fun tag(tagName: String): String? = originalInstance.tags.find { it.key == tagName }?.value

    fun sameZoneAs(reservation: CountedReservation) = (reservation.availabilityZone == this.availabilityZone) ||
            (reservation.scope == "Region" && this.availabilityZone.startsWith(reservation.location.region))

    fun sameTypeAs(reservation: CountedReservation) = (reservation.scope == "Region" && this.family == reservation.family)
                                                              || this.instanceType == reservation.instanceType

    fun sameProductAs(reservation: CountedReservation) = this.platform.equals(reservation.productDescription, ignoreCase = true)

    fun matches(reservation: CountedReservation) = reservation.let { sameZoneAs(it) && sameTypeAs(it) && sameProductAs(it) }
}

data class InstancedLoadBalancer(val originalLoadBalancer: LoadBalancerDescription, val location: Location): AWSResource {

    val name: String = originalLoadBalancer.loadBalancerName
    val httpPort: Int? = originalLoadBalancer.listenerDescriptions.forPort(80)?.instancePort
    val httpsPort: Int? = originalLoadBalancer.listenerDescriptions.forPort(443)?.instancePort
    val numberOfInstances: Int = originalLoadBalancer.instances.size
    val dnsName: String = originalLoadBalancer.dnsName
    var instances = listOf<MatchedInstance>()

    override val id: String = originalLoadBalancer.dnsName
    override var price: Float = 0.0f

    fun List<ListenerDescription>.forPort(port: Int): Listener? = this.filter { it.listener.loadBalancerPort == port}.firstOrNull()?.listener
}

data class RDSInstance(val originalInstance: DBInstance, val location: Location): AWSResource {

    val name: String? = originalInstance.dbInstanceIdentifier
    val type: String = originalInstance.dbInstanceClass
    val region: String = originalInstance.availabilityZone.dropLast(1)
    val engine: String = originalInstance.engine
    val engineVersion: String = originalInstance.engineVersion
    val multiAZ: Boolean = originalInstance.isMultiAZ
    val storage: Int = originalInstance.allocatedStorage
    val endpoint: String = "${originalInstance.endpoint.address}:${originalInstance.endpoint.port}"

    override val id: String = originalInstance.dbInstanceIdentifier
    override var price: Float = 0.0f
}

data class DomainName(val originalInstance: ResourceRecordSet): AWSResource {
    override val id: String = originalInstance.name
    override var price: Float = 0.0f

    val dnsName: String = originalInstance.name
    val type: String = originalInstance.type
    val ttl: Long? = originalInstance.ttl
    val target: String? = originalInstance.resourceRecords?.firstOrNull()?.value ?: originalInstance.aliasTarget?.dnsName
}

data class EBSVolume(val originalInstance: Volume, val location: Location): AWSResource {
    override var price: Float = 0.0f
    override val id: String = originalInstance.volumeId

    val name: String? = originalInstance.tags.firstOrNull { it.key == "Name" }?.value
    val size = originalInstance.size
    val iops = originalInstance.iops
    val encrypted = originalInstance.encrypted
    val state = originalInstance.state
    val type = originalInstance.volumeType
    val attachedInstanceIds = originalInstance.attachments.map { it.instanceId }
    var attachedInstances = listOf<MatchedInstance>()
}

data class Cache(val originalInstance: CacheCluster, val location: Location): AWSResource {

    override val id = originalInstance.cacheClusterId
    override var price = 0.0f

    val endpoint = originalInstance.configurationEndpoint?.run { "${address}:${port}" }
    val type = originalInstance.cacheNodeType
    val engine = originalInstance.engine
    val engineVersion = originalInstance.engineVersion
    val status = originalInstance.cacheClusterStatus
    val nodeCount = originalInstance.numCacheNodes

}

data class VPCSubnet(val originalInstance: Subnet, val location: Location): AWSResource {

    override val id = originalInstance.subnetId
    override var price = 0.0f

    val name = originalInstance.tags.firstOrNull { it.key == "Name" }?.value
    val vpc = originalInstance.vpcId
    val cidrBlock = originalInstance.cidrBlock
    val az = originalInstance.availabilityZone
    val default = originalInstance.isDefaultForAz
}

data class Check(val id: String, val name: String)

open class AdvisorResult(val check: Check, val region: String, val resourceType: String, val resourceId: String, val description: String, val saving: String = "", val rating: String = "None") {
    companion object {
        fun create(check: Check, rr: DescribeTrustedAdvisorCheckResultResult?): List<AdvisorResult> {
            rr ?: return emptyList()
            return rr.result.flaggedResources.map {
                val meta = it.metadata
                val region = if(meta.size > 1) meta[0] ?: "no region" else "no region"
                val resource = if(meta.size > 1) meta[1] ?: "unknown resource" else "unknown resource"
                try {
                    when (check.name) {
                        "Low Utilization Amazon EC2 Instances" -> AdvisorResult(check, region, "EC2", resource, description = "${meta[2]} is a ${meta[3]}", saving = meta[4])
                        "Idle Load Balancers" -> AdvisorResult(check, region, "ELB", resource, description = meta[2], saving = meta[3])
                        "Load Balancer Optimization " -> AdvisorResult(check, region, "ELB", resource, description = meta[9] ?: "", rating = meta[8])
                        "Unassociated Elastic IP Addresses" -> AdvisorResult(check, region, "EIP", resource, description = "IP is unused")
                        "Underutilized Amazon EBS Volumes" -> AdvisorResult(check, region, "EBS", resource, description = "Volume ${meta[2]}, ${meta[4]}Gb ${meta[3]}", saving = meta[5])
                        "Amazon EBS Snapshots" -> AdvisorResult(check, region, "EBS", resource, description = if (meta[8] == "Age") "Volume ${meta[2]} has a snapshot that is ${meta[5]} days old" else "Volume ${meta[2]} does not have a snapshot", rating = meta[7])
                        "Amazon EC2 Availability Zone Balance" -> AdvisorResult(check, region, "-", "-", description = "Region has an uneven balance of servers a:${meta[1]}, b:${meta[2]}, c:${meta[3]}, d:${meta[4]}, e:${meta[5]}", rating = meta[6])
                        "Amazon RDS Idle DB Instances" -> AdvisorResult(check, region, "RDS", resource, description = "RDS instance ${meta[1]} (${meta[3]}, ${meta[4]}Gb)is idle", saving = meta[6])

                        "Amazon RDS Multi-AZ" -> AdvisorResult(check, region, "RDS", resource, description = "RDS instance ${meta[1]} is in a single AZ", rating = meta[4])
                        "Amazon RDS Security Group Access Risk" -> AdvisorResult(check, region, "RDS", meta[1], description = "Ingress ${meta[2]}: ${meta[4]}", rating = meta[3])

                        "Service Limits" -> AdvisorResult(check, region, meta[1], "-", description = "Using ${meta[4]} of ${meta[3]} ${meta[2]}", rating = meta[5])
                        "Amazon Route 53 Alias Resource Record Sets" -> AdvisorResult(check, "-", "R53", meta[0], description = "Use an ALIAS to ${meta[2]} instead of a ${meta[3]}", rating = meta[6])
                        "Amazon Route 53 MX Resource Record Sets and Sender Policy Framework" -> AdvisorResult(check, "-", "R53", meta[0], description = "Domain ${meta[2]} does not have an SPF value")
                        "Amazon Route 53 Name Server Delegations" -> AdvisorResult(check, "-", "R53", meta[0], description = "${meta[0]} does not use the correct name server delegations")

                        "Amazon S3 Bucket Logging" -> AdvisorResult(check, region, "S3", resource, description = meta[7], rating = meta[6])
                        "Amazon S3 Bucket Permissions" -> AdvisorResult(check, region, "S3", meta[2], description = "Bucket has global permissions (List: ${meta[3]}, Upload/Delete: ${meta[4]})", rating = meta[5])
                        "Amazon S3 Bucket Versioning" -> AdvisorResult(check, region, "S3", resource, description = "Versioning is ${meta[2]}", rating = meta[4])

                        "Auto Scaling Group Health Check" -> AdvisorResult(check, region, "EC2", resource, description = "", rating = meta[4])

                        "Security Groups - Unrestricted Access" -> AdvisorResult(check, region, "EC2", resource, description = "Port ${meta[4]} (${meta[3]}) is open to ${meta[6]}", rating = meta[5])
                        "Security Groups - Specific Ports Unrestricted" -> AdvisorResult(check, region, "EC2", resource, description = "Port ${meta[5]} (${meta[3]}) is open", rating = meta[4])

                        "Overutilized Amazon EBS Magnetic Volumes" -> AdvisorResult(check, region, "EBS", resource, description = "Volume ${meta[2]} has been overutilized for ${meta[17]} days (max daily median ${meta[18]}%)", rating = meta[19])

                        "IAM Access Key Rotation" -> AdvisorResult(check, "-", "IAM", resource, description = "Access key has not been rotated for ${meta[4]}", rating = meta[0])
                        "ELB Security Groups" -> AdvisorResult(check, region, "ELB", resource, description = meta[4], rating = meta[2])
                        "ELB Listener Security" -> AdvisorResult(check, region, "ELB", resource, description = meta[4], rating = meta[3])
                        "ELB Cross-Zone Load Balancing" -> AdvisorResult(check, region, "ELB", resource, description = meta[3], rating = meta[2])
                        "ELB Connection Draining" -> AdvisorResult(check, region, "ELB", resource, description = meta[3], rating = meta[2])
                        "CloudFront SSL Certificate on the Origin Server" -> AdvisorResult(check, "-", "CloudFront", meta[2], description = meta[4], rating = meta[0])
                        "CloudFront Alternate Domain Names" -> AdvisorResult(check, "-", "CloudFront", meta[2], description = meta[4], rating = meta[0])
                        "AWS CloudTrail Logging" -> AdvisorResult(check, region, "CloudTrail", region, description = meta[2], rating = meta[5])
                        else -> AdvisorResult(check, region, "Unknown", resource, it.metadata.toString())
                    }
                } catch(e: Exception) {
                    val errorString = "Exception mapping ${it.metadata} for check '${check.name}': ${e.message}"
                    logger.error(errorString)
                    AdvisorResult(check, region, "Unknown", resource, errorString)
                }
            }.filterNotNull()
        }
    }
}