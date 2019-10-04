package com.revbingo.aws

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.regions.Region
import java.nio.file.Path
import java.nio.file.Paths

data class Profile(val name: String, val credentials: AwsCredentialsProvider? = null)
data class Location(val profile: Profile, val region: Region)

interface Accounts: Iterable<Profile> {
    val creds: Map<String, AwsCredentialsProvider>
    val regions: List<Region>

    fun <T> eachRegion(callback: (Location) -> List<T>): List<T> {
        return this.flatMap { profile ->
            regions.map { region ->
                callback(Location(profile, region))
            }
        }.flatten()
    }

    override fun iterator(): Iterator<Profile> = creds.map { entry -> Profile(entry.key, entry.value)}.iterator()
}

class AWSProfiles(filePath: String) : Accounts {

    override val creds = mutableMapOf<String, AwsCredentialsProvider>()

    init {
        val normalisedPath = filePath.replaceFirst("~", "/${System.getProperty("user.home")}")
        ProfileFile.builder()
                .content(Paths.get(normalisedPath))
            .build()
            .profiles()
            .mapValuesTo(creds) {
                ProfileCredentialsProvider.builder().profileName(it.key).build()
            }
    }

    override val regions = listOf(Region.US_EAST_1, Region.US_WEST_1, Region.US_WEST_2,  Region.US_EAST_2,
                                    Region.EU_WEST_1, Region.EU_WEST_2, Region.EU_CENTRAL_1,
                                    Region.AP_SOUTHEAST_1, Region.AP_SOUTHEAST_2)
}