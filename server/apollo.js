#!/usr/bin/env node
const fs = require('fs');
const casual = require('casual');
const path = require("path");
const {ApolloServer, gql} = require("apollo-server");

const roundDate = (millis) => {
  const date = new Date();
  return new Date(Math.floor(date.getTime() / millis) * millis)
}

const casualDate = () => {
  const date = new Date();

  if (casual.integer(0, 1)) {
    date.setHours(date.getHours() - casual.integer(0, 60));
  }

  if (casual.integer(0, 1)) {
    date.setMinutes(date.getMinutes() - casual.integer(0, 60));
  }

  if (casual.integer(0, 5)) {
    date.setDate(date.getDate() - casual.integer(0, 31));
  }

  if (casual.integer(0, 10) === 0) {
    date.setMonth(date.getMonth() - casual.integer(0, 12))
  }

  if (casual.integer(0, 49) === 0) {
    date.setFullYear(date.getFullYear() - casual.integer(0, 1));
  }
  return date;
};

const eksempler = {
  'Tilskudd': [
    "Avtale om midlertidig lønnstilskudd godkjent av alle",
    "Avtale om mentortilskudd mangler din godkjenning"
  ],
  'Rekrutering': [
    "Fire nye kandidater lagt til på listen \"Frontdesk medarbeidere\"",
    "To nye kandidater lagt til på listen \"Lagerarbeidere\"",
    "Fem nye kandidater lagt til på listen \"CTO\"",
  ],
  'Refusjon': [
    "Send inntekstmelding for søknad om foreldrepenger"
  ],
  'Sykemeldt': [
    "En ny sykmelding venter",
    "Svar på om du ønsker dialogmøte eller ikke"
  ]
};


const startApolloMock = () => {
  const data = fs.readFileSync(path.join(__dirname, 'bruker.graphql'));
  const typeDefs = gql(data.toString());
  const Notifikasjon = (navn) => {
    const merkelapp = casual.random_key(eksempler);
    const tekst = casual.random_element(eksempler[merkelapp]);

    return {
      __typename: navn, //casual.boolean ? 'Beskjed' : 'Oppgave',
      id: Math.random().toString(36),
      merkelapp,
      tekst,
      lenke: `#${casual.word}`,
      opprettetTidspunkt: casualDate().toISOString(),
      virksomhet: {
        navn: casual.random_element([
          "Ballstad og Hamarøy",
          "Saltrød og Høneby",
          "Arendal og Bønes Revisjon",
          "Gravdal og Solli Revisjon",
          "Storfonsa og Fredrikstad Regnskap"
        ])
      }
    };
  };
  const notifikasjoner = [...new Array(10)]
    .map(_ => Notifikasjon(casual.random_element(["Oppgave", "Beskjed"])))
    .sort((a, b) => b.opprettetTidspunkt.localeCompare(a.opprettetTidspunkt))
  const leggTilOgReturnerNotifikasjoner = () => {
    notifikasjoner.splice(0, 0, Notifikasjon(casual.random_element(["Oppgave", "Beskjed"])));
    if (notifikasjoner.length > 200) {
      notifikasjoner.splice(0, notifikasjoner.length - 200)
    }
    notifikasjoner.sort((a, b) => b.opprettetTidspunkt.localeCompare(a.opprettetTidspunkt));
    return notifikasjoner
  }
  new ApolloServer({
    typeDefs,
    cors: {
      origin: true,
      credentials: true,
    },
    mocks: {
      Query: () => ({
        notifikasjoner: () => ({
          notifikasjoner: leggTilOgReturnerNotifikasjoner(),
          feilAltinn: false,
          feilDigiSyfo: false,
        }),
      }),
      Int: () => casual.integer(0, 1000),
      String: () => casual.string,
      ISO8601DateTime: () => roundDate(5000).toISOString(),
      Virksomhet: () => ({
        navn: casual.catch_phrase,
      }),
    }
  }).listen({
    port: 8081,
    path: '/api/graphql',
  }).then(({url}) => {
    console.log(`🚀 gql server ready at ${url}`)
  });
}

startApolloMock()
