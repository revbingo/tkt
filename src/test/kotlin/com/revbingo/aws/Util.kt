package com.revbingo.aws

import com.amazonaws.services.ec2.model.*
import java.util.*

fun List<MatchedInstance>.matching() = this.filter { it.matched }

fun reservedInstance(count: Int = 1, az: String = "us-east-1a", type: String = "m3.large",
                     regionScope: Boolean = false, state: String = "active",
                     product: String = "Linux/UNIX", id: String = Random().nextInt().toString()): ReservedInstances {
    val ri = ReservedInstances()
    ri.instanceCount = count
    if(regionScope) {
        ri.scope = "Region"
    } else {
        ri.availabilityZone = az
    }
    ri.instanceType = type
    ri.state = state
    ri.productDescription = product
    ri.reservedInstancesId = id

    return ri
}

fun instance(az: String = "us-east-1a", type: String = "m3.large", state: String = "running",
             id: String = "noid", platform: String = "", vpcId: String? = null,
             dnsName: String? = null, tags: List<Tag> = emptyList(), publicIpAddress: String? = null,
            privateIpAddress: String? = null, keyName: String? = "key") : Instance {

    val instance = Instance()
    instance.instanceType = type
    instance.instanceId = id
    instance.state = InstanceState().withName(state)
    instance.placement = Placement().withAvailabilityZone(az)
    instance.platform = platform
    instance.vpcId = vpcId
    instance.launchTime = Date()
    instance.publicDnsName = dnsName
    instance.setTags(tags)
    instance.publicIpAddress = publicIpAddress
    instance.privateIpAddress = privateIpAddress
    instance.keyName = keyName

    return instance
}

fun countedReservations(count: Int = 1, az: String = "", type: String = "t2.large",
                      regionScope: Boolean = false, region: String = "", state: String = "active",
                        product: String = "Linux/UNIX", unmatchedCount: Int = count): List<CountedReservation> {
    val ri = reservedInstance(count, az, type, regionScope, state, product)

    return listOf(CountedReservation(ri, Location(Profile("Test"), region), unmatchedCount))
}

fun matchedInstances(count: Int = 1, az: String = "", type: String = "t2.large", state: String = "running",
              id: String = "noid", platform: String = "", vpcId: String? = null, dnsName: String? = null,
                     tags: List<Tag> = emptyList(), publicIpAddress: String? = null,
                     privateIpAddress: String? = null, keyName: String? = "key", accountName: String = "test") : List<MatchedInstance> {

    val instances = mutableListOf<Instance>()
    repeat(count) {
        val instance = instance(az, type, state, id, platform, vpcId, dnsName, tags, publicIpAddress, privateIpAddress, keyName)
        instances.add(instance)
    }
    return instances.map { MatchedInstance(it, Location(Profile(accountName), az.dropLast(1))) }
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