package com.revbingo.web


import com.nhaarman.mockitokotlin2.atLeast
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.revbingo.aws.Accounts
import com.revbingo.aws.Repository
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider

class ApplicationTest: Spek({

    describe("the application") {
        it("has a timer to trigger the repo update") {
            val mockRepo = mock<Repository>()
            val mockProfiles = mock<Accounts> {
                on { creds } doReturn mapOf("test" to StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret")))
            }
            val application = createApplication(updatePeriodMillis = 100, repository = mockRepo, accounts = mockProfiles)

            Thread.sleep(1000)

            verify(mockRepo, atLeast(10)).update()

            application.extinguish()
        }
    }
})
