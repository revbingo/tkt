package com.revbingo.aws

import software.amazon.awssdk.services.ec2.model.*
import java.time.Instant
import java.util.*

fun List<MatchedInstance>.matching() = this.filter { it.matched }

fun reservedInstance(count: Int = 1, az: String = "us-east-1a", type: String = "m3.large",
                     regionScope: Boolean = false, state: String = "active",
                     product: String = "Linux/UNIX", id: String = Random().nextInt().toString(),
                     endDate: Instant = Instant.now().plusSeconds(86400)): ReservedInstances {
    val ri = ReservedInstances.builder()
        .instanceCount(count)
        .instanceType(type)
        .state(state)
        .productDescription(product)
        .reservedInstancesId(id)
        .end(endDate)

    if(regionScope) {
        ri.scope("Region")
    } else {
        ri.availabilityZone(az)
    }

    return ri.build()
}

fun instance(az: String = "eu-west-1a", type: String = "m3.large", state: String = "running",
             id: String = "noid", platform: String = "", vpcId: String? = null, subnetId: String? = null,
             dnsName: String? = null, tags: List<Tag> = emptyList(), publicIpAddress: String? = null,
            privateIpAddress: String? = null, keyName: String? = "key", launchTime: Instant = Instant.now()) : Instance {

    val instance = Instance.builder()
        .instanceType(type)
        .instanceId(id)
        .state(InstanceState.builder().name(state).build())
        .placement(Placement.builder().availabilityZone(az).build())
        .platform(platform)
        .vpcId(vpcId)
        .subnetId(subnetId)
        .launchTime(launchTime)
        .publicDnsName(dnsName)
        .tags(tags)
        .publicIpAddress(publicIpAddress)
        .privateIpAddress(privateIpAddress)
        .keyName(keyName)

    return instance.build()
}

fun countedReservations(count: Int = 1, az: String = "", type: String = "t2.large",
                      regionScope: Boolean = false, region: String = "eu-west-1", state: String = "active",
                        product: String = "Linux/UNIX", unmatchedCount: Int = count): List<CountedReservation> {
    val ri = reservedInstance(count, az, type, regionScope, state, product)

    return listOf(CountedReservation(ri, Location(Profile("Test"), software.amazon.awssdk.regions.Region.of(region)), unmatchedCount))
}

fun matchedInstances(count: Int = 1, az: String = "eu-west-1a", type: String = "t2.large", state: String = "running",
              id: String = "noid", platform: String = "", vpcId: String? = null, subnetId: String? = null, subnet: VPCSubnet? = null, dnsName: String? = null,
                     tags: List<Tag> = emptyList(), publicIpAddress: String? = null,
                     privateIpAddress: String? = null, keyName: String? = "key", accountName: String = "test") : List<MatchedInstance> {

    val instances = mutableListOf<Instance>()
    repeat(count) {
        val instance = instance(az, type, state, id, platform, vpcId, subnet?.id ?: subnetId, dnsName, tags, publicIpAddress, privateIpAddress, keyName)
        instances.add(instance)
    }
    return instances.map { MatchedInstance(it, Location(Profile(accountName), software.amazon.awssdk.regions.Region.of(az.dropLast(1)))).apply {
        this.subnet = subnet
    } }
}

object ConfigParser {

    fun parse(config: String): List<ConfigHost> {
        val hosts = mutableListOf<ConfigHost>()
        var currentHost: ConfigHost? = null

        config.lines().forEach line@ { line ->
            if(line.isEmpty()) return@line
            val parts = line.trim().split(" ")

            when(parts[0]) {
                "Host" ->  {
                    currentHost = ConfigHost(parts[1])
                    hosts += currentHost!!
                }
                "User" ->  currentHost?.user = parts[1]
                "HostName" ->  currentHost?.hostName = parts[1]
                "IdentityFile" ->  currentHost?.identityFile = parts[1]
                "StrictHostKeyChecking" ->  currentHost?.strictHostKeyChecking = parts[1]
            }
        }
        return hosts
    }
}

data class ConfigHost(var host: String, var hostName: String? = null, var user: String? = null, var identityFile: String? = null, var strictHostKeyChecking: String? = null)