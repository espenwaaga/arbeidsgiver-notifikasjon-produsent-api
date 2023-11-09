package no.nav.arbeidsgiver.notifikasjon.ekstern_varsling

import com.fasterxml.jackson.databind.node.NullNode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import no.nav.arbeidsgiver.notifikasjon.hendelse.HendelseModel
import no.nav.arbeidsgiver.notifikasjon.hendelse.HendelseModel.EksterntVarselSendingsvindu.*
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.Database
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.kafka.suspendingSend
import no.nav.arbeidsgiver.notifikasjon.tid.asOsloLocalDateTime
import no.nav.arbeidsgiver.notifikasjon.util.testDatabase
import no.nav.arbeidsgiver.notifikasjon.util.uuid
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.time.Instant
import java.time.LocalDateTime
import java.util.*

class EksternVarslingStatusEksportServiceTest : DescribeSpec({
    val database = testDatabase(EksternVarsling.databaseConfig)
    val repository = EksternVarslingRepository(database)
    val kafka = mockk<KafkaProducer<String, VarslingStatusDto>>()
    val service = EksternVarslingStatusEksportService(
        eventSource = mockk(),
        repo = repository,
        kafka = kafka,
    )
    val hendelseMetadata = HendelseModel.HendelseMetadata(Instant.parse("2020-01-01T01:01:01.00Z"))
    val recordSlot = slot<ProducerRecord<String, VarslingStatusDto>>()

    beforeContainer {
        mockkStatic(KafkaProducer<String, VarslingStatusDto>::suspendingSend)
        coEvery {
            kafka.suspendingSend(capture(recordSlot))
        } returns mockk()
    }

    afterContainer {
        unmockkAll()
    }

    describe("EksternVarslingStatusEksportService#prosesserHendelse") {
        listOf(
            "30304",
            "30307",
            "30308",
        ).forEach { feilkode ->
            context("når hendelse er EksterntVarselFeilet med feilkode = $feilkode") {
                val varselTilstand = varselTilstand(uuid("314"), LØPENDE)
                database.insertVarselTilstand(varselTilstand)

                val event = eksterntVarselFeilet(feilkode, uuid("314"))
                service.testProsesserHendelse(event, hendelseMetadata)

                it("sender en VarslingStatusDto med status MANGLER_KOFUVI") {
                    recordSlot.captured.value().let {
                        it.status shouldBe Status.MANGLER_KOFUVI

                        it.virksomhetsnummer shouldBe event.virksomhetsnummer
                        it.varselId shouldBe event.varselId
                        it.varselTimestamp shouldBe varselTilstand.kalkuertSendetidspunkt(hendelseMetadata.timestamp.asOsloLocalDateTime())
                        it.kvittertEventTimestamp shouldBe hendelseMetadata.timestamp
                    }
                }
            }
        }


        context("når hendelse er EksterntVarselFeilet med feilkode = 42") {
            val varselTilstand = varselTilstand(
                uuid("314"),
                SPESIFISERT,
                LocalDateTime.parse("2021-01-01T01:01:01")
            )
            database.insertVarselTilstand(varselTilstand)

            val event = eksterntVarselFeilet("42", uuid("314"))
            service.testProsesserHendelse(event, hendelseMetadata)

            it("sender en VarslingStatusDto med status MANGLER_KOFUVI") {
                recordSlot.captured.value().let {
                    it.status shouldBe Status.ANNEN_FEIL

                    it.virksomhetsnummer shouldBe event.virksomhetsnummer
                    it.varselId shouldBe event.varselId
                    it.varselTimestamp shouldBe varselTilstand.data.eksternVarsel.sendeTidspunkt
                    it.kvittertEventTimestamp shouldBe hendelseMetadata.timestamp
                }
            }
        }

        context("når hendelse er EksterntVarselFeilet men varsel er harddeleted") {
            //coEvery {
            //    repository.findVarsel(uuid("314"))
            //} returns null // finnes ikke pga hard delete

            service.testProsesserHendelse(
                eksterntVarselFeilet("30308", uuid("314")),
                hendelseMetadata
            )

            it("sender ikke VarslingStatusDto") {
                coVerify {
                    kafka.suspendingSend(any()) wasNot Called
                }
            }
        }

        context("når hendelse er EksterntVarselVellykket men varsel er harddeleted") {
            //coEvery {
            //    repository.findVarsel(uuid("314"))
            //} returns null // finnes ikke pga hard delete

            service.testProsesserHendelse(
                eksterntVarselVellykket(uuid("314")),
                hendelseMetadata
            )

            it("sender ikke VarslingStatusDto") {
                coVerify {
                    kafka.suspendingSend(any()) wasNot Called
                }
            }
        }

        context("når hendelse er EksterntVarselVellykket") {
            val varselTilstand = varselTilstand(
                uuid("314"),
                SPESIFISERT,
                LocalDateTime.parse("2021-01-01T01:01:01")
            )
            database.insertVarselTilstand(varselTilstand)

            val event = eksterntVarselVellykket(uuid("314"))
            service.prosesserHendelse(
                event = event,
                meta = hendelseMetadata,
            )

            it("sender en VarslingStatusDto med status OK") {
                recordSlot.captured.value().let {
                    it.status shouldBe Status.OK

                    it.virksomhetsnummer shouldBe event.virksomhetsnummer
                    it.varselId shouldBe event.varselId
                    it.varselTimestamp shouldBe varselTilstand.data.eksternVarsel.sendeTidspunkt
                    it.kvittertEventTimestamp shouldBe hendelseMetadata.timestamp
                }
            }
        }
    }
})

private suspend fun EksternVarslingStatusEksportService.testProsesserHendelse(
    event: HendelseModel.Hendelse,
    hendelseMetadata: HendelseModel.HendelseMetadata
) {
    prosesserHendelse(
        event = event,
        meta = hendelseMetadata,
    )
}

private fun varselTilstand(
    varselId: UUID,
    sendeVindu: HendelseModel.EksterntVarselSendingsvindu,
    sendeTidspunkt: LocalDateTime? = null
) = EksternVarselTilstand.Sendt(
    data = EksternVarselStatiskData(
        varselId = varselId,
        notifikasjonId = uuid("1"),
        produsentId = "1",
        eksternVarsel = EksternVarsel.Sms(
            fnrEllerOrgnr = "",
            sendeVindu = sendeVindu,
            sendeTidspunkt = sendeTidspunkt,
            mobilnummer = "",
            tekst = ""
        )
    ),
    response = AltinnResponse.Feil(rå = NullNode.instance, feilkode = "", feilmelding = ""),
)

private fun eksterntVarselFeilet(altinnFeilkode: String, varselId: UUID) = HendelseModel.EksterntVarselFeilet(
    virksomhetsnummer = "1",
    notifikasjonId = uuid("1"),
    hendelseId = UUID.randomUUID(),
    produsentId = "1",
    kildeAppNavn = "1",
    varselId = varselId,
    råRespons = NullNode.instance,
    altinnFeilkode = altinnFeilkode,
    feilmelding = "oops"
)


private fun eksterntVarselVellykket(varselId: UUID) = HendelseModel.EksterntVarselVellykket(
    virksomhetsnummer = "1",
    notifikasjonId = uuid("1"),
    hendelseId = UUID.randomUUID(),
    produsentId = "1",
    kildeAppNavn = "1",
    varselId = varselId,
    råRespons = NullNode.instance
)

suspend fun Database.insertVarselTilstand(varselTilstand: EksternVarselTilstand.Sendt) {
    nonTransactionalExecuteUpdate(
        """
                insert into ekstern_varsel_kontaktinfo
                (
                    varsel_id,
                    notifikasjon_id,
                    notifikasjon_opprettet,
                    produsent_id,
                    varsel_type,
                    tlfnr,
                    fnr_eller_orgnr,
                    sms_tekst,
                    sendevindu,
                    sendetidspunkt,
                    state
                )
                values (?, ?, ?, ?, 'SMS', ?, ?, ?, ?, ?, 'NY');
                """
    ) {
        uuid(varselTilstand.data.varselId)
        uuid(varselTilstand.data.notifikasjonId)
        timestamp_without_timezone_utc(Instant.now())
        text(varselTilstand.data.produsentId)
        text("")
        text("")
        text("")
        text(varselTilstand.data.eksternVarsel.sendeVindu.toString())
        nullableText(varselTilstand.data.eksternVarsel.sendeTidspunkt?.toString())
    }

    nonTransactionalExecuteUpdate(
        """ 
                update ekstern_varsel_kontaktinfo
                set 
                    state = '${EksterntVarselTilstand.SENDT}',
                    altinn_response = ?::jsonb,
                    sende_status = ?::status,
                    feilmelding = ?,
                    altinn_feilkode = ?
                where varsel_id = ?
            """
    ) {
        jsonb(varselTilstand.response.rå)
        when (val r = varselTilstand.response) {
            is AltinnResponse.Feil -> {
                text("FEIL")
                nullableText(r.feilmelding)
                nullableText(r.feilkode)
            }

            is AltinnResponse.Ok -> {
                text("OK")
                nullableText(null)
                nullableText(null)
            }

            else -> {
                TODO("why does kotlin not know that this is exhaustive?")
            }
        }
        uuid(varselTilstand.data.varselId)
    }
}
