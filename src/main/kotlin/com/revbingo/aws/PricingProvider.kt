package com.revbingo.aws

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.Criteria.where
import com.jayway.jsonpath.Filter.filter
import com.jayway.jsonpath.JsonPath
import net.minidev.json.JSONArray
import java.io.File
import java.io.FileInputStream

interface PricingProvider {
    fun getPriceFor(instance: MatchedInstance): Float

}

/**
 *  Provides pricing information using the data gathered by ec2instances.info.  Note that this
 *  is not automatically downloaded, you should download the file and commit to data/instances.json
 *
 *  https://raw.githubusercontent.com/powdahound/ec2instances.info/master/www/instances.json
 */
class EC2InstancesDotInfoPricingProvider(pricingFile: File): PricingProvider {

    val pricingInfo: Any = Configuration.defaultConfiguration().jsonProvider().parse(FileInputStream(pricingFile), "utf-8")

    override fun getPriceFor(instance: MatchedInstance): Float {
        val instanceFilter = filter(where("instance_type").`is`(instance.instanceType))

        val os = if(instance.platform == "Windows") "mswin" else "linux"
        val reservation = if(instance.matched) "reserved.['yrTerm1Standard.noUpfront']" else "ondemand"

        val obj: JSONArray = JsonPath.read(pricingInfo, "$[?].pricing.${instance.region}.$os.$reservation", instanceFilter)
        val price : String = if(obj.size > 0) obj[0].toString() else "0"
        return price.toFloat()
    }
}
