package com.revbingo.aws

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.profile.ProfilesConfigFile
import com.amazonaws.regions.Regions

data class Profile(val name: String, val credentials: AWSCredentialsProvider? = null)
data class Location(val profile: Profile, val region: String)

interface Accounts: Iterable<Profile> {
    val creds: Map<String, Pair<String, String>>
    val regions: List<Regions>

    fun <T> eachRegion(callback: (Location) -> List<T>): List<T> {
        return this.flatMap { profile ->
            regions.map { region ->
                callback(Location(profile, region.getName()))
            }
        }.flatten()
    }

    override fun iterator(): Iterator<Profile> = creds.map { entry -> Profile(entry.key, AWSStaticCredentialsProvider(BasicAWSCredentials(entry.value.first, entry.value.second)))}.iterator()
}

class AWSProfiles(filePath: String) : Accounts {

    override val creds = mutableMapOf<String, Pair<String, String>>()

    init {
        val normalisedPath = filePath.replaceFirst("~", "/${System.getProperty("user.home")}")
        ProfilesConfigFile(normalisedPath).allBasicProfiles.mapValuesTo(creds) { Pair(it.value.awsAccessIdKey, it.value.awsSecretAccessKey) }
    }

    override val regions = listOf(Regions.US_EAST_1, Regions.US_WEST_1, Regions.US_WEST_2, Regions.EU_WEST_1, Regions.AP_SOUTHEAST_1, Regions.AP_SOUTHEAST_2)
//    override val regions = listOf(Regions.EU_WEST_1)
}