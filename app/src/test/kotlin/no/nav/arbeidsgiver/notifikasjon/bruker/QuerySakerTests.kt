package no.nav.arbeidsgiver.notifikasjon.bruker

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.beEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.ktor.server.testing.TestApplicationEngine
import no.nav.arbeidsgiver.notifikasjon.bruker.BrukerAPI.SakSortering.OPPRETTET
import no.nav.arbeidsgiver.notifikasjon.bruker.BrukerModel.Tilgang
import no.nav.arbeidsgiver.notifikasjon.bruker.BrukerModel.Tilganger
import no.nav.arbeidsgiver.notifikasjon.hendelse.HendelseModel.AltinnMottaker
import no.nav.arbeidsgiver.notifikasjon.hendelse.HendelseModel.NyStatusSak
import no.nav.arbeidsgiver.notifikasjon.hendelse.HendelseModel.SakOpprettet
import no.nav.arbeidsgiver.notifikasjon.hendelse.HendelseModel.SakStatus
import no.nav.arbeidsgiver.notifikasjon.hendelse.HendelseModel.SakStatus.FERDIG
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.graphql.GraphQLRequest
import no.nav.arbeidsgiver.notifikasjon.produsent.api.IdempotenceKey
import no.nav.arbeidsgiver.notifikasjon.util.AltinnStub
import no.nav.arbeidsgiver.notifikasjon.util.brukerApi
import no.nav.arbeidsgiver.notifikasjon.util.getTypedContent
import no.nav.arbeidsgiver.notifikasjon.util.ktorBrukerTestServer
import no.nav.arbeidsgiver.notifikasjon.util.testDatabase
import no.nav.arbeidsgiver.notifikasjon.util.uuid
import java.time.Duration
import java.time.OffsetDateTime
import java.util.*

class QuerySakerTests : DescribeSpec({
    val fallbackTimeNotUsed = OffsetDateTime.parse("2020-01-01T01:01:01Z")

    val database = testDatabase(Bruker.databaseConfig)
    val brukerRepository = BrukerRepositoryImpl(database)

    val engine = ktorBrukerTestServer(
        altinn = AltinnStub(
            "0".repeat(11) to Tilganger(
                tjenestetilganger = listOf(Tilgang.Altinn("42", "5441", "1"), Tilgang.Altinn("43", "5441", "1")),
            )
        ),
        brukerRepository = brukerRepository,
    )

    describe("Query.saker") {
        val sakOpprettet = SakOpprettet(
            hendelseId = uuid("0"),
            virksomhetsnummer = "42",
            produsentId = "test",
            kildeAppNavn = "test",
            sakId = uuid("0"),
            grupperingsid = "42",
            merkelapp = "tag",
            mottakere = listOf(AltinnMottaker("5441", "1", "42")),
            tittel = "er det no sak",
            lenke = "#foo",
            oppgittTidspunkt = OffsetDateTime.parse("2021-01-01T13:37:00Z"),
            mottattTidspunkt = OffsetDateTime.now(),
            hardDelete = null,
        )
        val statusSak = NyStatusSak(
            hendelseId = uuid("1"),
            virksomhetsnummer = sakOpprettet.virksomhetsnummer,
            produsentId = sakOpprettet.produsentId,
            kildeAppNavn = sakOpprettet.kildeAppNavn,
            sakId = sakOpprettet.sakId,
            status = SakStatus.MOTTATT,
            overstyrStatustekstMed = "noe",
            oppgittTidspunkt = OffsetDateTime.parse("2021-01-01T13:37:00Z"),
            mottattTidspunkt = OffsetDateTime.now(),
            idempotensKey = IdempotenceKey.initial(),
            hardDelete = null,
            nyLenkeTilSak = null,
        )

        context("med sak opprettet men ingen status") {
            brukerRepository.oppdaterModellEtterHendelse(sakOpprettet)

            val response = engine.hentSaker()

            it("response inneholder riktig data") {
                val sak = response.getTypedContent<BrukerAPI.Sak>("saker/saker/0")
                sak.id shouldBe sakOpprettet.sakId
                sak.merkelapp shouldBe "tag"
                sak.lenke shouldBe sakOpprettet.lenke
                sak.tittel shouldBe sakOpprettet.tittel
                sak.virksomhet.virksomhetsnummer shouldBe sakOpprettet.virksomhetsnummer
                sak.sisteStatus.tekst shouldBe "Mottatt"
                sak.sisteStatus.tidspunkt shouldBe sakOpprettet.opprettetTidspunkt(fallbackTimeNotUsed)
            }
        }

        context("med sak og status") {
            brukerRepository.oppdaterModellEtterHendelse(sakOpprettet)
            brukerRepository.oppdaterModellEtterHendelse(statusSak)

            val response = engine.hentSaker()

            it("response inneholder riktig data for sak") {
                val sak = response.getTypedContent<BrukerAPI.Sak>("saker/saker/0")
                sak.id shouldBe sakOpprettet.sakId
                sak.merkelapp shouldBe "tag"
                sak.lenke shouldBe sakOpprettet.lenke
                sak.tittel shouldBe sakOpprettet.tittel
                sak.virksomhet.virksomhetsnummer shouldBe sakOpprettet.virksomhetsnummer
                sak.sisteStatus.tekst shouldBe "noe"
            }
        }

        context("paginering med offset og limit angitt sortert på oppdatert") {
            val forventetRekkefoelge = listOf(
                uuid("3"),
                uuid("1"),
                uuid("4"),
            )

            brukerRepository.opprettSakMedTidspunkt(forventetRekkefoelge[0], Duration.ofHours(1), Duration.ofHours(5))
            brukerRepository.opprettSakMedTidspunkt(forventetRekkefoelge[1], Duration.ofHours(2), Duration.ofHours(4))
            brukerRepository.opprettSakMedTidspunkt(forventetRekkefoelge[2], Duration.ofHours(3))

            it("saksrekkefølge er korrekt innenfor page") {
                val response = engine.hentSaker(offset = 0, limit = 3)
                response.getTypedContent<BrukerAPI.Sak>("saker/saker/0").id shouldBe forventetRekkefoelge[0]
                response.getTypedContent<BrukerAPI.Sak>("saker/saker/1").id shouldBe forventetRekkefoelge[1]
                response.getTypedContent<BrukerAPI.Sak>("saker/saker/2").id shouldBe forventetRekkefoelge[2]
                response.getTypedContent<Int>("saker/totaltAntallSaker") shouldBe 3
            }

            it("sist oppdaterte sak først") {
                val response = engine.hentSaker(offset = 0, limit = 1)
                response.getTypedContent<BrukerAPI.Sak>("saker/saker/0").id shouldBe forventetRekkefoelge[0]
                response.getTypedContent<Int>("saker/totaltAntallSaker") shouldBe 3
            }

            it("mellomste sak ved offset 1") {
                val response = engine.hentSaker(offset = 1, limit = 1)
                response.getTypedContent<BrukerAPI.Sak>("saker/saker/0").id shouldBe forventetRekkefoelge[1]
                response.getTypedContent<Int>("saker/totaltAntallSaker") shouldBe 3
            }

            it("eldste sak ved offset 2") {
                val response = engine.hentSaker(offset = 2, limit = 1)
                response.getTypedContent<BrukerAPI.Sak>("saker/saker/0").id shouldBe forventetRekkefoelge[2]
                response.getTypedContent<Int>("saker/totaltAntallSaker") shouldBe 3
            }

            it("utenfor offset") {
                val response = engine.hentSaker(offset = 3, limit = 1)
                response.getTypedContent<List<Any>>("saker/saker") should beEmpty()
                response.getTypedContent<Int>("saker/totaltAntallSaker") shouldBe 3
            }

            it("offset og limit 0 gir fortsatt totalt antall saker") {
                val response = engine.hentSaker(offset = 0, limit = 0)
                response.getTypedContent<List<Any>>("saker/saker") should beEmpty()
                response.getTypedContent<Int>("saker/totaltAntallSaker") shouldBe 3
            }
        }

        context("paginering med offset og limit angitt sortert på opprettet") {
            val forventetRekkefoelge = listOf(
                uuid("3"),
                uuid("1"),
                uuid("4"),
            )

            brukerRepository.opprettSakMedTidspunkt(forventetRekkefoelge[0], Duration.ofHours(3))
            brukerRepository.opprettSakMedTidspunkt(forventetRekkefoelge[1], Duration.ofHours(2), Duration.ofHours(4))
            brukerRepository.opprettSakMedTidspunkt(forventetRekkefoelge[2], Duration.ofHours(1), Duration.ofHours(5))

            it("saksrekkefølge er korrekt innenfor page") {
                val response = engine.hentSaker(offset = 0, limit = 3, sortering = OPPRETTET)
                response.getTypedContent<BrukerAPI.Sak>("saker/saker/0").id shouldBe forventetRekkefoelge[0]
                response.getTypedContent<BrukerAPI.Sak>("saker/saker/1").id shouldBe forventetRekkefoelge[1]
                response.getTypedContent<BrukerAPI.Sak>("saker/saker/2").id shouldBe forventetRekkefoelge[2]
                response.getTypedContent<Int>("saker/totaltAntallSaker") shouldBe 3
            }

            it("sist oppdaterte sak først") {
                val response = engine.hentSaker(offset = 0, limit = 1, sortering = OPPRETTET)
                response.getTypedContent<BrukerAPI.Sak>("saker/saker/0").id shouldBe forventetRekkefoelge[0]
                response.getTypedContent<Int>("saker/totaltAntallSaker") shouldBe 3
            }

            it("mellomste sak ved offset 1") {
                val response = engine.hentSaker(offset = 1, limit = 1, sortering = OPPRETTET)
                response.getTypedContent<BrukerAPI.Sak>("saker/saker/0").id shouldBe forventetRekkefoelge[1]
                response.getTypedContent<Int>("saker/totaltAntallSaker") shouldBe 3
            }

            it("eldste sak ved offset 2") {
                val response = engine.hentSaker(offset = 2, limit = 1, sortering = OPPRETTET)
                response.getTypedContent<BrukerAPI.Sak>("saker/saker/0").id shouldBe forventetRekkefoelge[2]
                response.getTypedContent<Int>("saker/totaltAntallSaker") shouldBe 3
            }

            it("utenfor offset") {
                val response = engine.hentSaker(offset = 3, limit = 1, sortering = OPPRETTET)
                response.getTypedContent<List<Any>>("saker/saker") should beEmpty()
                response.getTypedContent<Int>("saker/totaltAntallSaker") shouldBe 3
            }
        }

        context("tekstsøk") {
            val sak1 = brukerRepository.opprettSakForTekstsøk("pippi langstrømpe er friskmeldt")
            val sak2 = brukerRepository.opprettSakForTekstsøk("donald duck er permittert", FERDIG, "saken er avblåst")

            it("søk på tittel returnerer riktig sak") {
                val response = engine.hentSaker(tekstsoek = "pippi")
                val saker = response.getTypedContent<List<BrukerAPI.Sak>>("saker/saker")
                saker shouldHaveSize 1
                saker.first().id shouldBe sak1.sakId
            }

            /** TAG-2137 ignored: vi skrudde av tekstsøk for status, siden vi hadde en
             * resource leak i forbindelse med replay av hendelser. Søketeksten
             * ble lengere for hver replay. Quick-fix var å bare bruke tittelen
             * på saken.
             */
            //xit("søk på status returnerer riktig sak") {
            //    val response = engine.hentSaker(tekstsoek = "ferdig")
            //    val saker = response.getTypedContent<List<BrukerAPI.Sak>>("saker/saker")
            //    saker shouldHaveSize 1
            //    saker.first().id shouldBe sak2.sakId
            //}
            //
            //xit("søk på statustekst returnerer riktig sak") {
            //    val response = engine.hentSaker(tekstsoek = "avblåst")
            //    val saker = response.getTypedContent<List<BrukerAPI.Sak>>("saker/saker")
            //    saker shouldHaveSize 1
            //    saker.first().id shouldBe sak2.sakId
            //}
        }

        context("søk på tvers av virksomheter") {
            val sak1 = brukerRepository.opprettSak(uuid("1"), "42")
            val sak2 = brukerRepository.opprettSak(uuid("2"), "43")

            it("hentSaker med tom liste av virksomhetsnumre gir tom liste") {
                val response = engine.hentSaker(virksomhetsnumre = listOf())
                val saker = response.getTypedContent<List<BrukerAPI.Sak>>("saker/saker")
                saker shouldHaveSize 0
            }

            it("hentSaker med liste av virksomhetsnumre=42 gir riktig sak") {
                val response = engine.hentSaker(listOf("42"))
                val saker = response.getTypedContent<List<BrukerAPI.Sak>>("saker/saker")
                saker shouldHaveSize 1
                saker.first().id shouldBe sak1.sakId
            }

            it("hentSaker med liste av virksomhetsnumre=43 gir riktig sak") {
                val response = engine.hentSaker(listOf("43"))
                val saker = response.getTypedContent<List<BrukerAPI.Sak>>("saker/saker")
                saker shouldHaveSize 1
                saker.first().id shouldBe sak2.sakId
            }

            it("hentSaker med liste av virksomhetsnumre=42,43 gir riktig sak") {
                val response = engine.hentSaker(virksomhetsnumre = listOf("42", "43"))
                val saker = response.getTypedContent<List<BrukerAPI.Sak>>("saker/saker")
                saker shouldHaveSize 2
            }
        }

        context("søk på type sak") {
            brukerRepository.opprettSak(uuid("1"), "42", "merkelapp1") // tilgang til 42
            brukerRepository.opprettSak(uuid("2"), "43", "merkelapp2") // tilgang til 43
            brukerRepository.opprettSak(uuid("3"), "44", "merkelapp3") // ikke tilgang til 44
            brukerRepository.opprettSak(uuid("4"), "45", "merkelapp1") // ikke tilgang til 45


            it("søk på null sakstyper returnere alle") {
                val response = engine.hentSaker(listOf("42", "43", "44", "45"))
                val saker = response.getTypedContent<List<UUID>>("$.saker.saker.*.id")
                saker shouldContainExactlyInAnyOrder listOf(uuid("1"), uuid("2"))

                val sakstyper = response.getTypedContent<List<String>>("$.saker.sakstyper.*.navn")
                sakstyper shouldContainExactlyInAnyOrder listOf("merkelapp1", "merkelapp2")
            }

            it("søk på merkelapp1") {
                val response = engine.hentSaker(listOf("42", "43", "44", "45"), sakstyper = listOf("merkelapp1"))
                val saker = response.getTypedContent<List<UUID>>("$.saker.saker.*.id")
                saker shouldContainExactlyInAnyOrder listOf(uuid("1"))

                val sakstyper = response.getTypedContent<List<String>>("$.saker.sakstyper.*.navn")
                sakstyper shouldContainExactlyInAnyOrder listOf("merkelapp1", "merkelapp2")
            }

            it("søk på merkelapp1 og merkelapp2") {
                val response = engine.hentSaker(listOf("42", "43", "44", "45"), sakstyper = listOf("merkelapp1", "merkelapp2"))
                val saker = response.getTypedContent<List<UUID>>("$.saker.saker.*.id")
                saker shouldContainExactlyInAnyOrder listOf(uuid("1"), uuid("2"))

                val sakstyper = response.getTypedContent<List<String>>("$.saker.sakstyper.*.navn")
                sakstyper shouldContainExactlyInAnyOrder listOf("merkelapp1", "merkelapp2")
            }

            it("søk på merkelapp3") {
                val response = engine.hentSaker(listOf("42", "43", "44", "45"), sakstyper = listOf("merkelapp3"))
                val saker = response.getTypedContent<List<UUID>>("$.saker.saker.*.id")
                saker shouldContainExactlyInAnyOrder listOf()

                val sakstyper = response.getTypedContent<List<String>>("$.saker.sakstyper.*.navn")
                sakstyper shouldContainExactlyInAnyOrder listOf("merkelapp1", "merkelapp2")
            }

            it("søk på tom liste") {
                val response = engine.hentSaker(listOf("42", "43", "44", "45"), sakstyper = listOf())
                val saker = response.getTypedContent<List<UUID>>("$.saker.saker.*.id")
                saker shouldContainExactlyInAnyOrder listOf()

                val sakstyper = response.getTypedContent<List<String>>("$.saker.sakstyper.*.navn")
                sakstyper shouldContainExactlyInAnyOrder listOf("merkelapp1", "merkelapp2")
            }
        }
    }
})

private suspend fun BrukerRepository.opprettSakForTekstsøk(
    tittel: String,
    status: SakStatus = SakStatus.MOTTATT,
    overstyrStatustekst: String? = null,
): SakOpprettet {
    val sakOpprettet = SakOpprettet(
        hendelseId = UUID.randomUUID(),
        virksomhetsnummer = "42",
        produsentId = "test",
        kildeAppNavn = "test",
        sakId = UUID.randomUUID(),
        grupperingsid = UUID.randomUUID().toString(),
        merkelapp = "tag",
        mottakere = listOf(AltinnMottaker("5441", "1", "42")),
        tittel = tittel,
        lenke = "#foo",
        oppgittTidspunkt = OffsetDateTime.parse("2021-01-01T13:37:00Z"),
        mottattTidspunkt = OffsetDateTime.now(),
        hardDelete = null,
    )
    oppdaterModellEtterHendelse(sakOpprettet)
    oppdaterModellEtterHendelse(
        NyStatusSak(
            hendelseId = UUID.randomUUID(),
            virksomhetsnummer = sakOpprettet.virksomhetsnummer,
            produsentId = sakOpprettet.produsentId,
            kildeAppNavn = sakOpprettet.kildeAppNavn,
            sakId = sakOpprettet.sakId,
            status = status,
            overstyrStatustekstMed = overstyrStatustekst,
            oppgittTidspunkt = OffsetDateTime.parse("2021-01-01T13:37:00Z"),
            mottattTidspunkt = OffsetDateTime.now(),
            idempotensKey = IdempotenceKey.initial(),
            hardDelete = null,
            nyLenkeTilSak = null,
        )
    )
    return sakOpprettet
}

private suspend fun BrukerRepository.opprettSakMedTidspunkt(
    sakId: UUID,
    opprettetShift: Duration,
    vararg restShift: Duration,
) {
    val shift = listOf(opprettetShift) + restShift
    val mottattTidspunkt = OffsetDateTime.parse("2022-01-01T13:37:30+02:00")
    val sak = SakOpprettet(
        hendelseId = sakId,
        sakId = sakId,
        grupperingsid = sakId.toString(),
        virksomhetsnummer = "42",
        produsentId = "test",
        kildeAppNavn = "test",
        merkelapp = "tag",
        mottakere = listOf(AltinnMottaker("5441", "1", "42")),
        tittel = "er det no sak",
        lenke = "#foo",
        oppgittTidspunkt = null,
        mottattTidspunkt = mottattTidspunkt.plus(opprettetShift),
        hardDelete = null,
    ).also {
        oppdaterModellEtterHendelse(it)
    }
    shift.forEach {
        NyStatusSak(
            hendelseId = UUID.randomUUID(),
            virksomhetsnummer = sak.virksomhetsnummer,
            produsentId = sak.produsentId,
            kildeAppNavn = sak.kildeAppNavn,
            sakId = sak.sakId,
            status = SakStatus.MOTTATT,
            overstyrStatustekstMed = "noe",
            oppgittTidspunkt = null,
            mottattTidspunkt = mottattTidspunkt.plus(it),
            idempotensKey = IdempotenceKey.initial(),
            hardDelete = null,
            nyLenkeTilSak = null,
        ).also { hendelse ->
            oppdaterModellEtterHendelse(hendelse)
        }
    }
}

private suspend fun BrukerRepository.opprettSak(
    sakId: UUID,
    virksomhetsnummer: String,
    merkelapp : String = "tag"
): SakOpprettet {
    val oppgittTidspunkt = OffsetDateTime.parse("2022-01-01T13:37:30+02:00")
    val sak = SakOpprettet(
        hendelseId = sakId,
        sakId = sakId,
        grupperingsid = sakId.toString(),
        virksomhetsnummer = virksomhetsnummer,
        produsentId = "test",
        kildeAppNavn = "test",
        merkelapp = merkelapp,
        mottakere = listOf(AltinnMottaker("5441", "1", virksomhetsnummer)),
        tittel = "er det no sak",
        lenke = "#foo",
        oppgittTidspunkt = oppgittTidspunkt,
        mottattTidspunkt = OffsetDateTime.now(),
        hardDelete = null,
    ).also {
        oppdaterModellEtterHendelse(it)
    }
    NyStatusSak(
        hendelseId = UUID.randomUUID(),
        virksomhetsnummer = sak.virksomhetsnummer,
        produsentId = sak.produsentId,
        kildeAppNavn = sak.kildeAppNavn,
        sakId = sak.sakId,
        status = SakStatus.MOTTATT,
        overstyrStatustekstMed = "noe",
        mottattTidspunkt = oppgittTidspunkt,
        idempotensKey = IdempotenceKey.initial(),
        oppgittTidspunkt = null,
        hardDelete = null,
        nyLenkeTilSak = null,
    ).also { hendelse ->
        oppdaterModellEtterHendelse(hendelse)
    }
    return sak
}

private fun TestApplicationEngine.hentSaker(
    virksomhetsnumre: List<String> = listOf("42"),
    sakstyper: List<String>? = null,
    tekstsoek: String? = null,
    offset: Int? = null,
    limit: Int? = null,
    sortering: BrukerAPI.SakSortering = BrukerAPI.SakSortering.OPPDATERT,
) = brukerApi(
    GraphQLRequest(
        """
    query hentSaker(${'$'}virksomhetsnumre: [String!]!, ${'$'}sakstyper: [String!], ${'$'}tekstsoek: String, ${'$'}sortering: SakSortering!, ${'$'}offset: Int, ${'$'}limit: Int){
        saker(virksomhetsnumre: ${'$'}virksomhetsnumre, sakstyper: ${'$'}sakstyper, tekstsoek: ${'$'}tekstsoek, sortering: ${'$'}sortering, offset: ${'$'}offset, limit: ${'$'}limit) {
            saker {
                id
                tittel
                lenke
                merkelapp
                virksomhet {
                    navn
                    virksomhetsnummer
                }
                sisteStatus {
                    type
                    tekst
                    tidspunkt
                }
                frister
                oppgaver {
                    frist
                    tilstand
                    paaminnelseTidspunkt
                }
                tidslinje {
                    ...on OppgaveTidslinjeElement {
                        tittel
                        status
                        paaminnelseTidspunkt
                        utgaattTidspunkt
                        utfoertTidspunkt
                        frist
                    }
                    ...on BeskjedTidslinjeElement {
                        tittel
                        opprettetTidspunkt
                    }
                }
            }
            sakstyper {
                navn
            }
            feilAltinn
            totaltAntallSaker
        }
    }
    """.trimIndent(),
        "hentSaker",
        mapOf(
            "virksomhetsnumre" to virksomhetsnumre,
            "sakstyper" to sakstyper,
            "tekstsoek" to tekstsoek,
            "sortering" to sortering,
            "offset" to offset,
            "limit" to limit,
        )
    )
)