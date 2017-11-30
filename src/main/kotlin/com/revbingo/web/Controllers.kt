package com.revbingo.web

import com.revbingo.aws.MatchedInstance
import com.revbingo.aws.Repository
import com.revbingo.aws.RepositoryViewModel
import com.revbingo.db.RepositoryHistory.databaseCount
import com.revbingo.db.RepositoryHistory.domainNameCount
import com.revbingo.db.RepositoryHistory.instanceCount
import com.revbingo.db.RepositoryHistory.loadBalancerCount
import com.revbingo.db.RepositoryHistory.matchedReservationCount
import com.revbingo.db.RepositoryHistory.reservedInstanceCount
import com.revbingo.db.RepositoryHistory.runningCount
import com.revbingo.db.RepositoryHistory.updateTime
import com.revbingo.db.RepositoryHistory.volumeCount
import com.revbingo.db.RepositoryHistory.vpcCount
import spark.ModelAndView
import kotlin.concurrent.thread

class UIController(private val repository: Repository) {

    fun repositoryListing(template: String): ModelAndView {
        return ModelAndView(RepositoryViewModel(repository), template)
    }

    fun updateRepository(): Unit {
        thread {
            repository.update()
        }
    }

}

class APIController(private val repository: Repository) {

    fun instancesForElb(elb: String): String? {
        val loadBalancer = repository.loadBalancers.find { it.name == elb } ?: return null

        val port = loadBalancer.httpPort ?: loadBalancer.httpsPort
        return loadBalancer.instances.render { "${it.publicDnsName}:$port" }
    }

    fun generateSshConfig(accountName: String? = null): String {
        return repository.validSshInstances(accountName).render { instance ->
            val ipAddress = instance.publicIpAddress ?: instance.privateIpAddress
            val id = instance.name?.toLowerCase()?.replace(" ", "-")
            val keyFile = "~/.ssh/${instance.originalInstance.keyName}.pem"

            var configEntry = """
            |Host ${id}
            |   HostName ${ipAddress}
            |   StrictHostKeyChecking no
            """.trimMargin()

            if(instance.tag("aws:cloudformation:stack-name") == null) {
                configEntry += """
                |
                |   User ec2-user
                |   IdentityFile ${keyFile}
                """.trimMargin()
            }

            configEntry += "\n"

            configEntry
        }
    }

    fun dashboardHistory(): String {
        return repository.databaseAccessor.getHistory().joinToString(separator = "\n", prefix = "timestamp,instances,running,invpc,reserved,matched,loadBalancers,databases,domains,volumes\n") {
            "${it[updateTime]},${it[instanceCount]},${it[runningCount]},${it[vpcCount]},${it[reservedInstanceCount]},${it[matchedReservationCount]},${it[loadBalancerCount]},${it[databaseCount]},${it[domainNameCount]},${it[volumeCount]}"
        }
    }

    private fun <T> List<T>.render(func: (T) -> String): String =
            this.foldRight(StringBuilder()) { it, buf -> buf.appendln(func(it)) }.toString()

    private fun Repository.validSshInstances(accountName: String? = null): List<MatchedInstance> =
            this.instances.filter {  it.inAccount(accountName) && it.isRunning
                                        && it.platform != "Windows" && it.originalInstance.keyName != null }

    fun MatchedInstance.inAccount(accountName: String? = null) = (accountName == null || this.location.profile.name == accountName)
}