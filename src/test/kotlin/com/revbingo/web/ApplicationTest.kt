package com.revbingo.web

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.nhaarman.mockito_kotlin.atLeast
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import com.revbingo.aws.Accounts
import com.revbingo.aws.Repository
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it

class ApplicationTest: Spek({

    describe("the application") {
        it("has a timer to trigger the repo update") {
            val mockRepo = mock<Repository>()
            val mockProfiles = mock<Accounts> {
                on { creds } doReturn mapOf("test" to AWSStaticCredentialsProvider(BasicAWSCredentials("key", "secret")))
            }
            val application = createApplication(updatePeriodMillis = 100, repository = mockRepo, accounts = mockProfiles)

            Thread.sleep(1000)

            verify(mockRepo, atLeast(10)).update()

            application.extinguish()
        }
    }
})
