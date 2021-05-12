package no.nav.arbeidsgiver.notifikasjon

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSingleElement
import kotlinx.coroutines.runBlocking
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.Database
import java.time.OffsetDateTime
import java.time.ZoneOffset.UTC
import java.time.temporal.ChronoUnit.MILLIS
import java.util.*

class QueryModelTests : DescribeSpec({
    val dataSource = runBlocking { Database.openDatabase() }
    val queryModel = QueryModel(dataSource)

    listener(PostgresTestListener(dataSource))


    describe("QueryModel") {
        describe("#oppdaterModellEtterBeskjedOpprettet()") {
            context("når event er BeskjedOpprettet") {
                val mottaker = FodselsnummerMottaker(
                    fodselsnummer = "314",
                    virksomhetsnummer = "1337"
                )
                val event = Hendelse.BeskjedOpprettet(
                    merkelapp = "foo",
                    eksternId = "42",
                    mottaker = mottaker,
                    guid = UUID.randomUUID(),
                    tekst = "teste",
                    grupperingsid = "gr1",
                    lenke = "foo.no/bar",
                    opprettetTidspunkt = OffsetDateTime.now(UTC).truncatedTo(MILLIS),
                    virksomhetsnummer = mottaker.virksomhetsnummer
                )

                queryModel.oppdaterModellEtterBeskjedOpprettet(event)

                it("opprettes beskjed i databasen") {
                    val notifikasjoner =
                        queryModel.hentNotifikasjoner(
                            mottaker.fodselsnummer,
                            emptyList()
                        )
                    notifikasjoner shouldHaveSingleElement QueryModel.QueryBeskjedMedId(
                        merkelapp = "foo",
                        eksternId = "42",
                        mottaker = mottaker,
                        tekst = "teste",
                        grupperingsid = "gr1",
                        lenke = "foo.no/bar",
                        opprettetTidspunkt = event.opprettetTidspunkt,
                        id = "1"
                    )
                }

                /* Ignorert: oppdateringen av modellen er veldig følsom på potensielt dupliserte meldinger. Når
                * den greier å detektere duplikater, skal den ikke kaste exception. */
                xcontext("duplikat av beskjed sendes") {
                    shouldNotThrowAny {
                        queryModel.oppdaterModellEtterBeskjedOpprettet(event)
                    }

                    it("beskjeden er uendret i databasen") {
                        val notifikasjoner =
                            queryModel.hentNotifikasjoner(
                                mottaker.fodselsnummer,
                                emptyList()
                            )
                        notifikasjoner shouldHaveSingleElement QueryModel.QueryBeskjedMedId(
                            merkelapp = "foo",
                            eksternId = "42",
                            mottaker = mottaker,
                            tekst = "teste",
                            grupperingsid = "gr1",
                            lenke = "foo.no/bar",
                            opprettetTidspunkt = event.opprettetTidspunkt,
                            id = "1"
                        )
                    }
                }

                context("modifikasjon av beskjeden sendes") {
                    val modifisertEvent = event.copy(
                        tekst = event.tekst + "noe annet"
                    )

                    shouldThrowAny {
                        queryModel.oppdaterModellEtterBeskjedOpprettet(modifisertEvent)
                    }

                    it("beskjeden er fortsatt uendret i databasen") {
                        val notifikasjoner =
                            queryModel.hentNotifikasjoner(
                                mottaker.fodselsnummer,
                                emptyList()
                            )
                        notifikasjoner shouldHaveSingleElement QueryModel.QueryBeskjedMedId(
                            merkelapp = "foo",
                            eksternId = "42",
                            mottaker = mottaker,
                            tekst = "teste",
                            grupperingsid = "gr1",
                            lenke = "foo.no/bar",
                            opprettetTidspunkt = event.opprettetTidspunkt,
                            id = "1"
                        )
                    }
                }
            }
        }
    }
})
