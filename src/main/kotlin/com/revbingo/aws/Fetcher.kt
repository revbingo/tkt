package com.revbingo.aws

import com.revbingo.web.logger
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourcesRequest
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.elasticache.ElastiCacheClient
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener
import software.amazon.awssdk.services.elasticloadbalancing.model.ListenerDescription
import software.amazon.awssdk.services.elasticloadbalancing.model.LoadBalancerDescription
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeListenersRequest
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest
import software.amazon.awssdk.services.rds.RdsClient
import software.amazon.awssdk.services.route53.Route53Client
import software.amazon.awssdk.services.route53.model.HostedZone
import software.amazon.awssdk.services.route53.model.ListResourceRecordSetsRequest
import software.amazon.awssdk.services.route53.model.ListResourceRecordSetsResponse
import software.amazon.awssdk.services.route53.model.ResourceRecordSet
import software.amazon.awssdk.services.support.SupportClient
import software.amazon.awssdk.services.support.model.*

interface Fetcher {
    fun getReservedInstances(): List<CountedReservation>
    fun getInstances(): List<MatchedInstance>
    fun getLoadBalancers(): List<InstancedLoadBalancer>
    fun getApplicationLoadBalancers(): List<InstancedLoadBalancer>
    fun getDatabases(): List<RDSInstance>
    fun getDomainNames(): List<DomainName>
    fun getTrustedAdvisorChecks(): List<Check>
    fun getAdvisorResults(checks: List<Check>): List<AdvisorResult>
    fun getVolumes(): List<EBSVolume>
    fun getCaches(): List<Cache>
    fun getSubnets(): List<VPCSubnet>
    fun getCloudformationStacks(): List<CFStack>
    fun getSpotInstanceRequests(): List<SpotRequest>
    fun getTargetGroups(): List<TargetGroup>
}

open class ClientGenerator(val accounts: Accounts) {

    @Suppress("UNCHECKED_CAST")
    fun <T, C: Any> eachLocation(clientBuilder: AwsClientBuilder<*, C>, callback: C.() -> List<T>): List<Pair<T, Location>> {
        return accounts.eachRegion { location ->
            val client = clientBuilder.credentialsProvider(location.profile.credentials)
                    .region(location.region).build() as C
            logger.debug("${client.javaClass} - ${location.profile.name} (${location.region})")
            client.callback().map { Pair(it, location)}
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T, C: Any> eachAccount(clientBuilder: AwsClientBuilder<*,C>, callback: C.(Profile) -> List<T>): List<T> {
        return accounts.flatMap {
            val client = clientBuilder.credentialsProvider(it.credentials).region(Region.US_EAST_1).build() as C
            logger.debug("${client.javaClass} - ${it.name}")
            client.callback(it)
        }
    }
}

class AWSFetcher(val clientGenerator: ClientGenerator): Fetcher {

    override fun getReservedInstances(): List<CountedReservation> {
        return clientGenerator.eachLocation(Ec2Client.builder()) {
            describeReservedInstances().reservedInstances()
        }.map { (original, location) ->
            CountedReservation(original, location)
        }.filter { it.isActive }
    }

    override fun getSubnets(): List<VPCSubnet> {
        return clientGenerator.eachLocation(Ec2Client.builder()) {
            describeSubnets().subnets()
        }.map { (original, location) ->
            VPCSubnet(original, location)
        }
    }

    override fun getInstances(): List<MatchedInstance> {
        return clientGenerator.eachLocation(Ec2Client.builder()) {
            describeInstances().reservations().flatMap { it.instances() }
        }.map { (original, location) ->
            MatchedInstance(original, location)
        }
    }

    override fun getLoadBalancers(): List<InstancedLoadBalancer> {
        return clientGenerator.eachLocation(ElasticLoadBalancingClient.builder()) {
            describeLoadBalancers().loadBalancerDescriptions()
        }.map { (original, location) ->
            InstancedLoadBalancer(original, location, "Classic")
        }
    }

    override fun getApplicationLoadBalancers(): List<InstancedLoadBalancer> {
        val clientBuilder = ElasticLoadBalancingV2Client.builder()
        return clientGenerator.eachLocation(clientBuilder) {
            describeLoadBalancers(DescribeLoadBalancersRequest.builder().build()).loadBalancers().map {
                ApplicationLoadBalancer(it,
                    describeListeners(DescribeListenersRequest.builder().loadBalancerArn(it.loadBalancerArn()).build()).listeners(),
                    describeTargetGroups(DescribeTargetGroupsRequest.builder()
                                            .loadBalancerArn(it.loadBalancerArn())
                                            .build()
                                        ).targetGroups().map { tg -> TargetGroup(tg)})
            }
        }.map { (alb, location) ->

            val mappedDescription = LoadBalancerDescription.builder()
                    .listenerDescriptions(
                        alb.listeners.map { l: software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener ->
                            ListenerDescription.builder()
                                .listener(Listener.builder()
                                        .instancePort(0)
                                        .protocol(l.protocol().name)
                                        .loadBalancerPort(l.port())
                                        .build()
                                )
                                .build()
                    })
                    .dnsName(alb.originalLoadBalancer.dnsName())
                    .loadBalancerName(alb.originalLoadBalancer.loadBalancerName())
                    .build()

            InstancedLoadBalancer(mappedDescription, location, "Application")
        }
    }

    override fun getDatabases(): List<RDSInstance> {
        return clientGenerator.eachLocation(RdsClient.builder()) {
            describeDBInstances().dbInstances()
        }.map { (original, location) ->
            RDSInstance(original, location)
        }
    }

    override fun getVolumes(): List<EBSVolume> {
        return clientGenerator.eachLocation(Ec2Client.builder()) {
            describeVolumes().volumes()
        }.map { (original, location) ->
            EBSVolume(original, location)
        }
    }

    override fun getDomainNames(): List<DomainName> {
        return clientGenerator.eachAccount(Route53Client.builder()) {
            listHostedZones().hostedZones().flatMap { zone -> getRecordSets(zone) }
        }.filter {
            it.typeAsString() == "A" || it.typeAsString() == "CNAME"
        }.map(::DomainName)
    }

    override fun getCaches(): List<Cache> {
        return clientGenerator.eachLocation(ElastiCacheClient.builder()) {
            describeCacheClusters().cacheClusters()
        }.map { (original, location) ->
            Cache(original, location)
        }
    }

    override fun getTrustedAdvisorChecks(): List<Check> {
        return clientGenerator.eachAccount(SupportClient.builder()) { (accountName, _) ->
            try {
                describeTrustedAdvisorChecks(DescribeTrustedAdvisorChecksRequest.builder().language("en").build()).checks()
            } catch(e: SupportException) {
                logger.warn("Skipping ${accountName}, TrustedAdvisor only available for Premium Support accounts")
                emptyList<TrustedAdvisorCheckDescription>()
            }
        }.distinct().map {
            Check(it.id(), it.name())
        }
    }

    override fun getAdvisorResults(checks: List<Check>): List<AdvisorResult> {
        return clientGenerator.eachAccount(SupportClient.builder()) { (accountName, _) ->
            try {
                checks.map {
                    Pair(it, describeTrustedAdvisorCheckResult(DescribeTrustedAdvisorCheckResultRequest.builder().checkId(it.id).build()))
                }
            } catch(e: SupportException) {
                logger.warn("Skipping ${accountName}, TrustedAdvisor only available for Premium Support accounts")
                emptyList<Pair<Check, DescribeTrustedAdvisorCheckResultResponse>>()
            }
        }.flatMap { (check, results) ->
            AdvisorResult.create(check, results)
        }
    }

    override fun getCloudformationStacks(): List<CFStack> {
        try {
            return clientGenerator.eachLocation(CloudFormationClient.builder()) {
                describeStacks().stacks()
            }.map { (stack, location) ->
                CFStack(stack, location).apply {
                    retry(3) {
                        logger.debug("Fetching resources for stack ${stack.stackName()}")
                        this.resourceIds = CloudFormationClient.builder().credentialsProvider(location.profile.credentials)
                                .region(location.region)
                                .build()
                                .describeStackResources(DescribeStackResourcesRequest.builder()
                                        .stackName(stack.stackName())
                                        .build()).stackResources().map { r ->
                            CFResource(r.physicalResourceId(), r.resourceType())
                        }
                    }
                }
            }
        } catch(e: Exception) {
            return emptyList()
        }
    }

    override fun getSpotInstanceRequests(): List<SpotRequest> {
        return clientGenerator.eachLocation(Ec2Client.builder()) {
            describeSpotInstanceRequests().spotInstanceRequests()
        }.map{ (spotRequest, location) ->
            SpotRequest(spotRequest)
        }
    }

    override fun getTargetGroups(): List<TargetGroup> {
        var clientBuilder = ElasticLoadBalancingV2Client.builder()
        return clientGenerator.eachLocation(clientBuilder) {
            val request = DescribeTargetGroupsRequest.builder().build()
            describeTargetGroups(request).targetGroups()
        }.map {
            TargetGroup(it.first)
        }
    }

    fun <T> retry(times: Int, swallow: Boolean = false, lambda: () -> T) {
        var lastException: Throwable? = null
        for(i in 0 until times) {
            try {
                lambda()
            } catch(e:Exception) {
                lastException = e
                logger.warn("Error ${e.message}.  Retrying in ${i * 1000} millseconds")
                Thread.sleep((i * 1000).toLong())
            }
        }
        if(!swallow && lastException != null) throw lastException
    }

}

/* EXTENSIONS */

private fun Route53Client.getRecordSets(zone: HostedZone, previousResult: ListResourceRecordSetsResponse? = null): Collection<ResourceRecordSet> {
    if(previousResult != null && !previousResult.isTruncated()) return emptyList()

    val results = this.listResourceRecordSets(previousResult.nextRequest(zone.id()))
    return results.resourceRecordSets().union(this.getRecordSets(zone, results))
}

private fun ListResourceRecordSetsResponse?.nextRequest(zone: String): ListResourceRecordSetsRequest {
    val request = ListResourceRecordSetsRequest.builder().hostedZoneId(zone)
    this ?: return request.build()

    return request
            .startRecordIdentifier(this@nextRequest.nextRecordIdentifier())
            .startRecordName(this@nextRequest.nextRecordName())
            .startRecordType(this@nextRequest.nextRecordType())
            .build()
}

