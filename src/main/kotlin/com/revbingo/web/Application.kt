package com.revbingo.web

import com.revbingo.aws.*
import com.revbingo.db.DatabaseAccessor
import joptsimple.OptionParser
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spark.Spark.*
import spark.template.mustache.MustacheTemplateEngine
import java.io.File
import java.util.*
import kotlin.concurrent.fixedRateTimer

val logger: Logger = LoggerFactory.getLogger("main")

class Application(val uiController: UIController, val apiController: APIController, val updateThread: Timer? = null) {

    var ignited = false

    private fun mapToTemplate(route: String, template: String) {
        get(route, { _, _ -> uiController.repositoryListing(template) }, MustacheTemplateEngine())
    }

    fun ignite() {
        staticFileLocation("/assets")

        mapToTemplate("/", "ec2.mustache")
        mapToTemplate("/elb", "elb.mustache")
        mapToTemplate("/rds", "rds.mustache")
        mapToTemplate("/route53", "route53.mustache")
        mapToTemplate("/volumes", "volumes.mustache")
        mapToTemplate("/caches", "caches.mustache")
        mapToTemplate("/subnets", "subnets.mustache")
        mapToTemplate("/dashboard", "dashboard.mustache")
        mapToTemplate("/checks", "checks.mustache")
        mapToTemplate("/stacks", "stacks.mustache")

        get("/dashboard/history") { _, res ->
            res.type("text/plain")
            apiController.dashboardHistory()
        }

        get("/update") { _, res ->
            uiController.updateRepository()
            res.redirect("/")
        }

        get("/api/ssh") { req, res ->
            res.type("text/plain")
            apiController.generateSshConfig(req.queryParams("account"))
        }

        get("/api/instances") { req, res ->
            res.type("text/plain")

            val elbName = req.queryParams("name").orEmpty()

            if (elbName.isNotEmpty()) {
                val body = apiController.instancesForElb(elbName)
                when (body) {
                    is String -> body
                    else -> {
                        res.status(404); ""
                    }
                }
            } else {
                res.status(400)
                "name parameter must be specified"
            }
        }

        exception(Exception::class.java) { exc, _, res ->
            logger.error(exc.message)
            res.body(exc.message)
        }

        awaitInitialization()
        ignited = true
    }

    fun extinguish() {
        updateThread?.cancel()
        if(ignited) stop()
    }
}

fun createApplication(updatePeriodMillis: Long = 60 * 60 * 1000,
                      accounts: Accounts,
                      fetcher: Fetcher = AWSFetcher(ClientGenerator(accounts)),
                      pricingFile: File = File("data/instances.json"),
                      pricingProvider: PricingProvider = EC2InstancesDotInfoPricingProvider(pricingFile),
                      databaseAccessor: DatabaseAccessor = DatabaseAccessor.NoOp(),
                      useAdvisor: Boolean = true,
                      repository: Repository = Repository(fetcher, pricingProvider, databaseAccessor, useAdvisor),
                      uiController: UIController = UIController(repository),
                      apiController: APIController = APIController(repository)): Application {

    val repoUpdate = fixedRateTimer(name = "updateThread", daemon = true, period = updatePeriodMillis) {
        repository.update()
    }

    logger.info("Waiting for repository update")

    //block until repository is initialised
    while(repository.updating) {
        Thread.sleep(1000)
    }

    return Application(uiController, apiController, repoUpdate)
}

fun main(args: Array<String>) {

    val parser = OptionParser("i:")
    parser.accepts("creds").withRequiredArg()
    parser.accepts("db").withRequiredArg()
    parser.accepts("no-advisor")

    val options = parser.parse(*args)

    val credsFile = if(options.has("creds")) options.valueOf("creds") as String else "~/.aws/credentials"
    val pricingFile = if(options.has("i")) File(options.valueOf("i") as String) else File("data/instances.json")
    val dbLocation = if(options.has("db")) options.valueOf("db") as String else "./db/repo"
    val useAdvisor = !options.has("no-advisor")

    val application = createApplication(accounts = AWSProfiles(credsFile), pricingFile =  pricingFile, databaseAccessor = DatabaseAccessor.H2(dbLocation), useAdvisor = useAdvisor)

    logger.info("Starting application")
    application.ignite()
}