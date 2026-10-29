package it.ristomanager.cloud.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpokenTextTest {

    private static String it(String text) {
        return SpokenText.forSpeech(text, "it");
    }

    private static String en(String text) {
        return SpokenText.forSpeech(text, "en");
    }

    // ---- Italian

    @Test
    void aTimeRangeAndACountAreSpokenAsWords() {
        assertThat(it("12:00-13:45: 5 tavoli")).isEqualTo("dalle dodici alle tredici e quarantacinque: cinque tavoli");
        assertThat(it("12:00 - 13:45")).isEqualTo("dalle dodici alle tredici e quarantacinque");
    }

    @Test
    void aSingleTimeKeepsTheWordBeforeIt() {
        assertThat(it("20:30")).isEqualTo("le venti e trenta");
        assertThat(it("Prenotato alle 20:30 per 4")).isEqualTo("Prenotato alle venti e trenta per quattro");
        assertThat(it("alle 21:00")).isEqualTo("alle ventuno");
        assertThat(it("alle 13:00 e dalle 1:15")).isEqualTo("alle tredici e dall'una e quindici");
        assertThat(it("a ore 19:45")).isEqualTo("a ore diciannove e quarantacinque");
        assertThat(it("01:00")).isEqualTo("l'una");
    }

    @Test
    void anInvalidTimeIsLeftAsItWas() {
        assertThat(it("alle 25:99")).isEqualTo("alle 25:99");
    }

    @Test
    void numbersBecomeWords() {
        assertThat(it("Siamo in 23 al tavolo 7")).isEqualTo("Siamo in ventitré al tavolo sette");
        assertThat(it("121 coperti, 180 e 1.250")).isEqualTo("centoventuno coperti, centottanta e milleduecentocinquanta");
        assertThat(it("2026 persone")).isEqualTo("duemilaventisei persone");
        assertThat(it("33000")).isEqualTo("trentatremila");
        assertThat(it("3,5 kg")).isEqualTo("tre virgola cinque kg");
        assertThat(it("20% di sconto")).isEqualTo("venti per cento di sconto");
    }

    @Test
    void oneAgreesWithTheNounOrStaysUno() {
        assertThat(it("1 tavolo e 1 persona")).isEqualTo("un tavolo e una persona");
        assertThat(it("Tavolo 1 è libero")).isEqualTo("Tavolo uno è libero");
        assertThat(it("1 spritz")).isEqualTo("uno spritz");
    }

    @Test
    void numbersGluedToLettersOrOfAnUnknownShapeAreLeftAlone() {
        assertThat(it("Tavolo T12 e 4pax")).isEqualTo("Tavolo T12 e 4pax");
        assertThat(it("versione 1.2.3")).isEqualTo("versione 1.2.3");
        assertThat(it("punteggio 8/10")).isEqualTo("punteggio 8/10");
        assertThat(it("aperti 24/7")).isEqualTo("aperti 24/7");
    }

    @Test
    void datesAreSpokenInTheLanguage() {
        assertThat(it("il 29/10")).isEqualTo("il ventinove ottobre");
        assertThat(it("il 2026-10-29")).isEqualTo("il ventinove ottobre duemilaventisei");
        assertThat(it("il 15/03/2027")).isEqualTo("il quindici marzo duemilaventisette");
        assertThat(it("dal 1 novembre")).isEqualTo("dal primo novembre");
        assertThat(it("il 31/02/2026")).isEqualTo("il 31/02/2026");
    }

    @Test
    void moneyIsSpokenAsEuros() {
        assertThat(it("Conto: 12,50 €")).isEqualTo("Conto: dodici euro e cinquanta");
        assertThat(it("€45")).isEqualTo("quarantacinque euro");
        assertThat(it("1 euro")).isEqualTo("un euro");
        assertThat(it("1.250,00 euro")).isEqualTo("milleduecentocinquanta euro");
        assertThat(it("0,50€")).isEqualTo("cinquanta centesimi");
    }

    @Test
    void phoneNumbersAreReadDigitByDigit() {
        assertThat(it("Chiama 333 123 4567")).isEqualTo("Chiama tre tre tre, uno due tre, quattro cinque sei sette");
        assertThat(it("+39 06 1234567")).isEqualTo("più tre nove, zero sei, uno due tre quattro cinque sei sette");
        assertThat(it("3331234567")).isEqualTo("tre tre tre uno due tre quattro cinque sei sette");
    }

    @Test
    void rangesAndSymbolsAreSpoken() {
        assertThat(it("2-4 persone")).isEqualTo("da due a quattro persone");
        assertThat(it("Tavolo 3 - 4 persone")).isEqualTo("Tavolo tre, quattro persone");
        assertThat(it("Pizza & birra")).isEqualTo("Pizza e birra");
        assertThat(it("Tavolo 3 - vicino alla finestra")).isEqualTo("Tavolo tre, vicino alla finestra");
        assertThat(it("antipasti e/o primi")).isEqualTo("antipasti e o primi");
        assertThat(it("**Stasera** `ok` # nota")).isEqualTo("Stasera ok nota");
        assertThat(it("pre-ordine")).isEqualTo("pre-ordine");
    }

    // ---- English

    @Test
    void englishTimes() {
        assertThat(en("12:00-13:45: 5 tables")).isEqualTo("from noon to one forty-five p.m.: five tables");
        assertThat(en("at 20:30")).isEqualTo("at eight thirty p.m.");
        assertThat(en("19:05-21:00")).isEqualTo("from seven oh five to nine p.m.");
        assertThat(en("10:00-14:00")).isEqualTo("from ten a.m. to two p.m.");
        assertThat(en("00:00")).isEqualTo("midnight");
    }

    @Test
    void englishNumbersDatesAndMoney() {
        assertThat(en("23 guests at table 7")).isEqualTo("twenty-three guests at table seven");
        assertThat(en("1,250 and 3.5")).isEqualTo("one thousand two hundred fifty and three point five");
        assertThat(en("on 29/10")).isEqualTo("on October twenty-ninth");
        assertThat(en("2026-10-29")).isEqualTo("October twenty-ninth, twenty twenty-six");
        assertThat(en("5/3/2005")).isEqualTo("March fifth, two thousand five");
        assertThat(en("€12.50 or 1 euro or 20%")).isEqualTo("twelve euros and fifty cents or one euro or twenty percent");
        assertThat(en("Burger & fries - 3rd table")).isEqualTo("Burger and fries, 3rd table");
        assertThat(en("phone +39 333 123 4567")).isEqualTo("phone plus three nine, three three three, one two three, four five six seven");
        assertThat(en("4-6 people")).isEqualTo("four to six people");
    }

    // ---- shared

    @Test
    void otherLanguagesAndMissingOnesAreSpokenAsEnglish() {
        assertThat(SpokenText.forSpeech("5 tables", null)).isEqualTo("five tables");
        assertThat(SpokenText.forSpeech("5 tavoli", "it-IT")).isEqualTo("cinque tavoli");
        assertThat(SpokenText.forSpeech("5 tables", "fr")).isEqualTo("five tables");
    }

    @Test
    void plainTextAndEdgeCasesComeBackUnchanged() {
        assertThat(it("Ciao, come posso aiutarti?")).isEqualTo("Ciao, come posso aiutarti?");
        assertThat(en("Hello there.")).isEqualTo("Hello there.");
        assertThat(SpokenText.forSpeech(null, "it")).isEmpty();
        assertThat(SpokenText.forSpeech("   ", "it")).isEqualTo("   ");
        assertThat(it("***")).isEqualTo("***");
        assertThat(it("99999999999999999999")).isEqualTo("99999999999999999999");
    }
}
