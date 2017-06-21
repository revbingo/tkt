package com.revbingo.aws

import com.amazonaws.client.builder.AwsSyncClientBuilder
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.elasticache.AmazonElastiCacheClientBuilder
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClientBuilder
import com.amazonaws.services.rds.AmazonRDSClientBuilder
import com.amazonaws.services.route53.AmazonRoute53
import com.amazonaws.services.route53.AmazonRoute53ClientBuilder
import com.amazonaws.services.route53.model.HostedZone
import com.amazonaws.services.route53.model.ListResourceRecordSetsRequest
import com.amazonaws.services.route53.model.ListResourceRecordSetsResult
import com.amazonaws.services.route53.model.ResourceRecordSet
import com.amazonaws.services.support.AWSSupportClientBuilder
import com.amazonaws.services.support.model.*
import com.revbingo.web.logger

interface Fetcher {
    fun getReservedInstances(): List<CountedReservation>
    fun getInstances(): List<MatchedInstance>
    fun getLoadBalancers(): List<InstancedLoadBalancer>
    fun getDatabases(): List<RDSInstance>
    fun getDomainNames(): List<DomainName>
    fun getTrustedAdvisorChecks(): List<Check>
    fun getAdvisorResults(checks: List<Check>): List<AdvisorResult>
    fun getVolumes(): List<EBSVolume>
    fun getCaches(): List<Cache>
}

open class ClientGenerator(val accounts: Accounts) {

    @Suppress("UNCHECKED_CAST")
    fun <T, C: Any> eachLocation(clientBuilder: AwsSyncClientBuilder<*,C>, callback: C.() -> List<T>): List<Pair<T, Location>> {
        return accounts.eachRegion { location ->
            val client = clientBuilder.withCredentials(location.profile.credentials)
                    .withRegion(location.region).build() as C
            logger.debug("${client.javaClass} - ${location.profile.name} (${location.region})")
            client.callback().map { Pair(it, location)}
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T, C: Any> eachAccount(clientBuilder: AwsSyncClientBuilder<*,C>, callback: C.(Profile) -> List<T>): List<T> {
        return accounts.flatMap {
            val client = clientBuilder.withCredentials(it.credentials).withRegion(Regions.US_EAST_1).build() as C
            logger.debug("${client.javaClass} - ${it.name}")
            client.callback(it)
        }
    }
}

class AWSFetcher(val clientGenerator: ClientGenerator): Fetcher {

    override fun getReservedInstances(): List<CountedReservation> {
        return clientGenerator.eachLocation(AmazonEC2ClientBuilder.standard()) {
            describeReservedInstances().reservedInstances
        }.map { (original, location) ->
            CountedReservation(original, location)
        }.filter { it.isActive }
    }

    override fun getInstances(): List<MatchedInstance> {
        return clientGenerator.eachLocation(AmazonEC2ClientBuilder.standard()) {
            describeInstances().reservations.flatMap { it.instances }
        }.map { (original, location) ->
            MatchedInstance(original, location)
        }
    }

    override fun getLoadBalancers(): List<InstancedLoadBalancer> {
        return clientGenerator.eachLocation(AmazonElasticLoadBalancingClientBuilder.standard()) {
            describeLoadBalancers().loadBalancerDescriptions
        }.map { (original, location) ->
            InstancedLoadBalancer(original, location)
        }
    }

    override fun getDatabases(): List<RDSInstance> {
        return clientGenerator.eachLocation(AmazonRDSClientBuilder.standard()) {
            describeDBInstances().dbInstances
        }.map { (original, location) ->
            RDSInstance(original, location)
        }
    }

    override fun getVolumes(): List<EBSVolume> {
        return clientGenerator.eachLocation(AmazonEC2ClientBuilder.standard()) {
            describeVolumes().volumes
        }.map { (original, location) ->
            EBSVolume(original, location)
        }
    }

    override fun getDomainNames(): List<DomainName> {
        return clientGenerator.eachAccount(AmazonRoute53ClientBuilder.standard()) {
            listHostedZones().hostedZones.flatMap { zone -> getRecordSets(zone) }
        }.filter {
            it.type == "A" || it.type == "CNAME"
        }.map(::DomainName)
    }

    override fun getCaches(): List<Cache> {
        return clientGenerator.eachLocation(AmazonElastiCacheClientBuilder.standard()) {
            describeCacheClusters().cacheClusters
        }.map { (original, location) ->
            Cache(original, location)
        }
    }

    override fun getTrustedAdvisorChecks(): List<Check> {
        return clientGenerator.eachAccount(AWSSupportClientBuilder.standard()) { (accountName, _) ->
            try {
                describeTrustedAdvisorChecks(DescribeTrustedAdvisorChecksRequest().withLanguage("en")).checks
            } catch(e: AWSSupportException) {
                logger.warn("Skipping ${accountName}, TrustedAdvisor only available for Premium Support accounts")
                emptyList<TrustedAdvisorCheckDescription>()
            }
        }.distinct().map {
            Check(it.id, it.name)
        }
    }

    override fun getAdvisorResults(checks: List<Check>): List<AdvisorResult> {
        return clientGenerator.eachAccount(AWSSupportClientBuilder.standard()) { (accountName, _) ->
            try {
                checks.map {
                    Pair(it, describeTrustedAdvisorCheckResult(DescribeTrustedAdvisorCheckResultRequest().withCheckId(it.id)))
                }
            } catch(e: AWSSupportException) {
                logger.warn("Skipping ${accountName}, TrustedAdvisor only available for Premium Support accounts")
                emptyList<Pair<Check, DescribeTrustedAdvisorCheckResultResult>>()
            }
        }.flatMap { (check, results) ->
            AdvisorResult.create(check, results)
        }
    }
}

/* EXTENSIONS */

private fun AmazonRoute53.getRecordSets(zone: HostedZone, previousResult: ListResourceRecordSetsResult? = null): Collection<ResourceRecordSet> {
    if(previousResult != null && !previousResult.isTruncated()) return emptyList()

    val results = this.listResourceRecordSets(previousResult.nextRequest(zone.id))
    return results.resourceRecordSets.union(this.getRecordSets(zone, results))
}

private fun ListResourceRecordSetsResult?.nextRequest(zone: String): ListResourceRecordSetsRequest {
    val request = ListResourceRecordSetsRequest(zone)
    this ?: return request

    return request.apply {
        withStartRecordIdentifier(this@nextRequest.nextRecordIdentifier).withStartRecordName(this@nextRequest.nextRecordName).withStartRecordType(this@nextRequest.nextRecordType)
    }
}

