package no.nav.arbeidsgiver.notifikasjon

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.beEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.string.beBlank
import io.ktor.http.*
import io.mockk.*
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.*
import org.apache.kafka.clients.producer.Producer
import java.util.*
import java.util.concurrent.CompletableFuture

class KlikkPåNotifikasjonGraphQLTest: DescribeSpec({
    val altinn: Altinn = mockk()
    val queryModel: QueryModel = mockk(relaxed = true)
    val kafkaProducer: CoroutineProducer<KafkaKey, Hendelse> = mockk()

    val engine = ktorTestServer(
        brukerGraphQL = BrukerAPI.createBrukerGraphQL(
            altinn = altinn,
            queryModelFuture = CompletableFuture.completedFuture(queryModel),
            kafkaProducer = kafkaProducer
        ),
        produsentGraphQL = mockk()
    )

    mockkStatic(CoroutineProducer<KafkaKey, Hendelse>::brukerKlikket)
    coEvery { any<CoroutineProducer<KafkaKey, Hendelse>>().brukerKlikket(any()) } returns Unit

    afterSpec {
        unmockkAll()
    }

    describe("bruker-api: rapporterer om at notifikasjon er klikket på") {

        context("uklikket-notifikasjon eksisterer for bruker") {
            val id = UUID.fromString("09d5a598-b31a-11eb-8529-0242ac130003")
            coEvery { queryModel.virksomhetsnummerForNotifikasjon(id) } returns "1234"
            val query = """
                    mutation {
                        notifikasjonKlikketPaa(id: "$id") {
                            errors {
                                __typename
                                feilmelding
                            }
                        }
                    }
                """.trimIndent()

            val httpResponse = engine.post(
                "/api/graphql",
                host = BRUKER_HOST,
                jsonBody = GraphQLRequest(query),
                accept = "application/json",
                authorization = "Bearer $SELVBETJENING_TOKEN"
            )

            it("ingen http/graphql-feil") {
                httpResponse.status() shouldBe HttpStatusCode.OK
                httpResponse.getGraphqlErrors() should beEmpty()
            }

            val graphqlSvar = httpResponse.getTypedContent<BrukerAPI.NotifikasjonKlikketPaaResultat>("notifikasjonKlikketPaa")

            it("ingen domene-feil") {
                graphqlSvar.errors should beEmpty()
            }

            val brukerKlikketMatcher: MockKAssertScope.(Hendelse.BrukerKlikket) -> Unit = { brukerKlikket ->
                brukerKlikket.fnr shouldNot beBlank()
                brukerKlikket.notifikasjonsId shouldBe id

                /* For øyeblikket feiler denne, siden virksomhetsnummer ikke hentes ut. */
                brukerKlikket.virksomhetsnummer shouldNot beBlank()
            }

            it("Event produseres på kafka") {
                coVerify {
                    any<CoroutineProducer<KafkaKey, Hendelse>>().brukerKlikket(
                        withArg(brukerKlikketMatcher)
                    )
                }
            }

            it("Database oppdaters") {
                coVerify {
                    queryModel.oppdaterModellEtterBrukerKlikket(
                        withArg(brukerKlikketMatcher)
                    )
                }
            }
        }
    }
})