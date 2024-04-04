package no.nav.arbeidsgiver.notifikasjon.util

import io.kotest.core.TestConfiguration
import io.kotest.core.listeners.BeforeContainerListener
import io.kotest.core.test.TestCase
import no.nav.arbeidsgiver.notifikasjon.hendelse.HendelseModel
import no.nav.arbeidsgiver.notifikasjon.hendelse.HendelseProdusent
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.kafka.ValueDeserializer
import no.nav.arbeidsgiver.notifikasjon.infrastruktur.kafka.ValueSerializer
import java.time.Instant
import java.util.*

fun TestConfiguration.fakeHendelseProdusent(): FakeHendelseProdusent {
    return FakeHendelseProdusent().also {
        register(object: BeforeContainerListener {
            override suspend fun beforeContainer(testCase: TestCase) {
                it.hendelser.clear()
            }
        })
    }

}

open class FakeHendelseProdusent: HendelseProdusent {
    val serializer = ValueSerializer()
    val deserializer = ValueDeserializer()
    val hendelser = mutableListOf<HendelseModel.Hendelse>()

    inline fun <reified T> hendelserOfType() = hendelser.filterIsInstance<T>()

    override suspend fun sendOgHentMetadata(hendelse: HendelseModel.Hendelse) : HendelseModel.HendelseMetadata {
        val data = serializer.serialize("", hendelse)
        hendelser.add(deserializer.deserialize("", data)!!)
        return HendelseModel.HendelseMetadata(Instant.parse("1970-01-01T00:00:00Z"))
    }

    override suspend fun tombstone(key: UUID, orgnr: String) {
        hendelser.removeIf {
            it.hendelseId == key
        }
    }

    fun clear() {
        hendelser.clear()
    }
}