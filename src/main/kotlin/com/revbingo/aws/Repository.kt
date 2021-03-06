package com.revbingo.aws

import com.revbingo.db.DatabaseAccessor
import com.revbingo.web.logger
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.runBlocking
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

open class Repository(val fetcher: Fetcher, val pricingProvider: PricingProvider, val databaseAccessor: DatabaseAccessor, private val useAdvisor: Boolean = true) {

    var allResources = mutableMapOf<String, AWSResource>()

    open var reservedInstances = emptyList<CountedReservation>()
    open var instances = emptyList<MatchedInstance>()
    open var loadBalancers = emptyList<InstancedLoadBalancer>()
    open var domainNames = emptyList<DomainName>()
    open var databases = emptyList<RDSInstance>()
    open var volumes = emptyList<EBSVolume>()
    open var caches = emptyList<Cache>()
    open var subnets = emptyList<VPCSubnet>()
    open var stacks = emptyList<CFStack>()
    open var spots = emptyList<SpotRequest>()
    open var targetGroups = emptyList<TargetGroup>()

    open var checks = emptyList<Check>()
    open var checkResults = emptyList<AdvisorResult>()

    open var instanceIdMap: Map<String, MatchedInstance> = mutableMapOf()
    open var subnetMap: Map<String, VPCSubnet> = mutableMapOf()

    open var updateTime: LocalDateTime = LocalDateTime.now()
    open var updating = true

    open var inError: Boolean = false
    open var errorMessage: String? = null

    private val pool = newFixedThreadPoolContext(10, "fetcher")

    open fun update() = runBlocking {
        logger.info("Updating repository")
        updating = true

        try {
            inError = false

            if (useAdvisor) async(pool) { updateAdvisories() }

            val reservedInstancesJob = async(pool) { fetcher.getReservedInstances() }
            val instancesJob = async(pool) { fetcher.getInstances() }
            val loadBalancersJob = async(pool) { fetcher.getLoadBalancers() }
            val appLoadBalancersJob = async(pool) { fetcher.getApplicationLoadBalancers() }
            val databasesJob = async(pool) { fetcher.getDatabases()}
            val domainNamesJob = async(pool) { fetcher.getDomainNames() }
            val volumesJob = async(pool) { fetcher.getVolumes() }
            val cachesJob = async(pool) { fetcher.getCaches() }
            val subnetsJob = async(pool) { fetcher.getSubnets() }
            val stacksJob = async(pool) { fetcher.getCloudformationStacks() }
            val spotsJob = async(pool) { fetcher.getSpotInstanceRequests() }
            val tgJob = async(pool) { fetcher.getTargetGroups() }

            reservedInstances = reservedInstancesJob.await()
            instances = instancesJob.await()
            loadBalancers = loadBalancersJob.await().union(appLoadBalancersJob.await()).toList()
            databases = databasesJob.await()
            domainNames = domainNamesJob.await()
            volumes = volumesJob.await()
            caches = cachesJob.await()
            subnets = subnetsJob.await()
            stacks = stacksJob.await()
            spots = spotsJob.await()
            targetGroups = tgJob.await()

            val allResourcesList = mutableListOf<AWSResource>()
            allResourcesList.addAll(reservedInstances)
            allResourcesList.addAll(instances)
            allResourcesList.addAll(loadBalancers)
            allResourcesList.addAll(databases)
            allResourcesList.addAll(domainNames)
            allResourcesList.addAll(volumes)
            allResourcesList.addAll(caches)
            allResourcesList.addAll(subnets)
            allResourcesList.addAll(stacks)
            allResourcesList.addAll(spots)
            allResourcesList.addAll(targetGroups)

            allResourcesList.associateByTo(allResources, { it.id })

            instanceIdMap = instances.associateBy { it.instanceId }
            subnetMap = subnets.associateBy { it.id }

            updateInstancesInLoadBalancers()
            updateInstancesInVolumes()
            updateSubnetsInInstances()
            matchReservationsToInstances()
            markAsCloudformed()

            applyPricing()

            updateTime = LocalDateTime.now()

            persistToDatabase()
        } catch(e: Exception) {
            logger.error("Error occurred during repository update", e)
            inError = true
            errorMessage = e.message
        } finally {
            logger.info("Update completed")
            updating = false
        }
    }

    private fun applyPricing() {
        instances.forEach {
            it.price =  when {
                it.isSpotInstance() -> allResources[it.originalInstance.spotInstanceRequestId()]?.price ?: 0.0f
                else -> pricingProvider.getPriceFor(it)
            }
        }
    }

    private fun matchReservationsToInstances() {
        ReservationMatcher(reservedInstances).match(instances)
    }

    private fun updateInstancesInLoadBalancers() {
        loadBalancers.forEach { lb ->
            lb.instances = lb.originalLoadBalancer.instances().map { instanceIdMap[it.instanceId()] }.filterNotNull()
        }

        targetGroups.forEach { tg ->
            tg.originalInstance.loadBalancerArns()
        }
    }

    private fun updateInstancesInVolumes() {
        volumes.forEach { volume ->
            volume.attachedInstances = volume.attachedInstanceIds.map { instanceIdMap[it] }.filterNotNull()
        }
    }

    private fun updateSubnetsInInstances() {
        instances.forEach { instance ->
            instance.subnet = subnetMap[instance.subnetId]
        }
    }

    private fun markAsCloudformed() {
        stacks.forEach { stack ->
            stack.resourceIds.forEach { (physicalId, _) ->
                allResources[physicalId]?.apply {
                    stackName = stack.name
                }
            }
        }
    }

    private fun persistToDatabase() {
        databaseAccessor.persist(this)
    }

    private fun updateAdvisories() {
        //only do this once at startup
        if (checks.isEmpty()) {
            checks = fetcher.getTrustedAdvisorChecks()
        }
        checkResults = fetcher.getAdvisorResults(checks)
    }

    public fun totalComputeUnitsPerFamily(): Map<String, Double> {
        return instances.groupBy {
            "${it.family} (${it.platform}) - ${it.region}"
        }.mapValues { entry ->
            entry.value.sumByDouble { instance ->
                instance.computeUnits.toDouble()
            }
        }
    }

}

open class RepositoryViewModel(val repository: Repository) {
    open fun lastRefreshTime(): String = if(!repository.updating) DateTimeFormatter.ISO_DATE_TIME.format(repository.updateTime) else "(Refreshing now)"
    open fun refreshAvailable(): Boolean = !repository.updating

    open fun reservations() = repository.reservedInstances
    open fun instances() = repository.instances
    open fun loadBalancers() = repository.loadBalancers
    open fun databases() = repository.databases
    open fun domainNames() = repository.domainNames
    open fun volumes() = repository.volumes
    open fun caches() = repository.caches
    open fun subnets() = repository.subnets
    open fun stacks() = repository.stacks

    open fun advisorResults() = repository.checkResults

    open fun instanceCount(): Int = repository.instances.count()
    open fun runningCount(): Int = repository.instances.count { it.isRunning }

    open fun usedReservedCount(): Double = repository.reservedInstances.sumByDouble { it.usedCapacity().toDouble() }
    open fun unusedReservedCount(): Double = repository.reservedInstances.sumByDouble { it.unusedCapacity().toDouble() }
    open fun totalReservedCount(): Double = usedReservedCount() + unusedReservedCount()

    open fun vpcCount(): Int = repository.instances.count { it.isVpc }

    open fun instancePct(): Int = instanceCount().asPercentage()
    open fun runningPct(): Int = runningCount().asPercentage()

    open fun inError(): Boolean = repository.inError
    open fun errorMessage(): String? = repository.errorMessage

    fun Int.asPercentage():Int = (this * 100)/(instanceCount())

    open fun totalCostPerHour(): Double = repository.instances.filter { it.isRunning }.sumByDouble { it.price.toDouble() }
    open fun totalComputeUnits(): Set<Map.Entry<String, Double>> = repository.totalComputeUnitsPerFamily().entries
    open fun formattedCost(): String = "%.2f".format(totalCostPerHour())
}
