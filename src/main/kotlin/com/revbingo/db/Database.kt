package com.revbingo.db

import com.revbingo.aws.Repository
import com.revbingo.aws.RepositoryViewModel
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.math.BigDecimal
import java.time.ZoneOffset

sealed class DatabaseAccessor() {

    constructor(database: Database): this() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns (RepositoryHistory)
        }
    }

    open fun persist(repo: Repository) {
        val viewWrapper = RepositoryViewModel(repo)
        transaction {
            RepositoryHistory.insert {
                it[updateTime] = DateTime(repo.updateTime.toInstant(ZoneOffset.UTC).toEpochMilli())
                it[loadBalancerCount] = viewWrapper.loadBalancers().count()
                it[reservedInstanceCount] = BigDecimal.valueOf(viewWrapper.totalReservedCount())
                it[matchedReservationCount] = BigDecimal.valueOf(viewWrapper.unusedReservedCount())
                it[instanceCount] = viewWrapper.instanceCount()
                it[runningCount] = viewWrapper.runningCount()
                it[vpcCount] = viewWrapper.vpcCount()
                it[databaseCount] = viewWrapper.databases().count()
                it[domainNameCount] = viewWrapper.domainNames().count()
                it[volumeCount] = viewWrapper.volumes().count()
                it[cost] = BigDecimal.valueOf(viewWrapper.totalCostPerHour())
            }
        }
    }

    open fun getHistory(): List<ResultRow> {
        return transaction {
            RepositoryHistory.selectAll().toList()
        }
    }

    open class H2(dbLocation: String): DatabaseAccessor(Database.connect("jdbc:h2:${dbLocation}", driver = "org.h2.Driver"))

    class H2InMemory(): H2("mem")

    class NoOp: DatabaseAccessor() {
        override fun persist(repo: Repository) {}
        override fun getHistory(): List<ResultRow> { return emptyList() }
    }
}

object RepositoryHistory: Table() {
    val updateTime = datetime("updateTime")
    val reservedInstanceCount = decimal("riCount", 5, 2)
    val matchedReservationCount = decimal("matchedRI", 5, 2)
    val instanceCount = integer("instanceCount")
    val runningCount = integer("runningCount").default(0)
    val vpcCount = integer("vpcCount").default(0)
    val databaseCount = integer("dbCount")
    val domainNameCount = integer("dnsCount")
    val loadBalancerCount = integer("elbCount")
    val volumeCount = integer("ebsCount").default(0)
    val cost = decimal("totalCost", precision = 10, scale = 2)
}