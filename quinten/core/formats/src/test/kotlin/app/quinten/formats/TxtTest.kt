package app.quinten.formats

import app.quinten.formats.txt.TxtParser
import app.quinten.formats.util.FileTitle
import app.quinten.formats.util.TextEncoding
import app.quinten.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class TxtTest {
    private val chinese = "第一章\n\n我们在这个小城里住了很多年，他说时间过得很快。大家都知道，这是一个美好的地方，人们对生活有很多想法。" +
        "她看着天上的月亮，心里想起了自己的家。我们没有说话，只是一起走到了河边。后来他们发现了一个新的问题，可是谁也不知道应该怎么办。\n"
    private val traditional = "第一章\n\n我們在這個小城裡住了很多年，他說時間過得很快。大家都知道，這是一個美好的地方，人們對生活有很多想法。" +
        "她看著天上的月亮，心裡想起了自己的家。我們沒有說話，只是一起走到了河邊。後來他們發現了一個新的問題，可是誰也不知道應該怎麼辦。\n"
    private val japanese = "第一章\n\n吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。" +
        "吾輩はここで始めて人間というものを見た。しかもあとで聞くとそれは書生という人間中で一番獰悪な種族であったそうだ。\n"
    private val korean = "제1장\n\n나는 그 작은 마을에서 오랫동안 살았다. 그는 시간이 아주 빨리 지나간다고 말했다. 우리는 모두 그것이 아름다운 곳이라는 것을 알고 있었다. " +
        "그녀는 하늘의 달을 보면서 자기 집을 생각했다. 우리는 아무 말도 하지 않고 함께 강가로 걸어갔다. 나중에 그들은 새로운 문제를 발견했지만 누구도 어떻게 해야 할지 몰랐다.\n"
    private val polish = "Rozdział pierwszy\n\nLitwo! Ojczyzno moja! ty jesteś jak zdrowie. Ile cię trzeba cenić, ten tylko się dowie, kto cię stracił. " +
        "Dziś piękność twą w całej ozdobie widzę i opisuję, bo tęsknię po tobie. Panno Święta, co jasnej bronisz Częstochowy i w Ostrej świecisz Bramie! Żółć, łąka, źdźbło.\n"
    private val czech = "Kapitola první\n\nMilý čtenáři, tato kniha vypráví příběh, který se odehrál v malém městě na řece. Když přišel večer, všichni se sešli v hospodě a mluvili o tom, " +
        "co se stalo. Řekl jsem jim, že zítra půjdeme do lesa, kde je mnoho hub a ještě více stromů. Děti se smály a já jsem věděl, že to bude krásný den.\n"
    private val russian = "Глава первая\n\nВсе счастливые семьи похожи друг на друга, каждая несчастливая семья несчастлива по-своему. Все смешалось в доме Облонских. " +
        "Жена узнала, что муж был в связи с бывшею в их доме француженкою-гувернанткой, и объявила мужу, что не может жить с ним в одном доме.\n"
    private val french = "Chapitre premier\n\nLongtemps, je me suis couché de bonne heure. Parfois, à peine ma bougie éteinte, mes yeux se fermaient si vite que je n'avais pas le temps de me dire : « Je m'endors. » " +
        "Et, une demi-heure après, la pensée qu'il était temps de chercher le sommeil m'éveillait ; je voulais poser le volume que je croyais avoir dans les mains.\n"

    private fun roundTrip(text: String, charset: String) {
        val bytes = text.repeat(20).toByteArray(Charset.forName(charset))
        val guessed = TextEncoding.guess(bytes)
        assertEquals("guess for $charset", Charset.forName(charset), guessed)
        assertEquals(text.repeat(20), TxtParser.decode(bytes))
    }

    @Test fun detectsGbk() = roundTrip(chinese, "GB18030")
    @Test fun detectsGbkEncodedAsGbk() {
        val bytes = chinese.repeat(20).toByteArray(Charset.forName("GBK"))
        assertEquals(chinese.repeat(20), TxtParser.decode(bytes))
    }
    @Test fun detectsBig5() = roundTrip(traditional, "Big5")
    @Test fun detectsShiftJis() = roundTrip(japanese, "Shift_JIS")
    @Test fun detectsEucKr() = roundTrip(korean, "EUC-KR")
    @Test fun detectsPolish1250() = roundTrip(polish, "windows-1250")
    @Test fun detectsCzech1250() = roundTrip(czech, "windows-1250")
    @Test fun keepsCp1251() = roundTrip(russian, "windows-1251")
    @Test fun keepsKoi8() = roundTrip(russian, "KOI8-R")
    @Test fun keepsCp1252() = roundTrip(french, "windows-1252")
    @Test fun keepsUtf8() = roundTrip(chinese + polish, "UTF-8")

    @Test fun bomIsHonoured() {
        val b = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hé".toByteArray(Charsets.UTF_16LE)
        assertEquals("Hé", TxtParser.decode(b))
    }

    @Test fun paragraphsAndChapters() {
        val text = "CHAPTER I\r\n\r\nFirst line of a rather long paragraph that keeps going for a while and is para-\r\ngraph that goes on with more words than fit on one short line.\r\n\r\nSecond\tparagraph.\r\n\r\nCHAPTER II\r\n\r\nMore text.\r\n"
        val doc = TxtParser.parseText(text, null)
        assertEquals(listOf("CHAPTER I", "CHAPTER II"), doc.toc.map { it.title })
        val blocks = doc.loadChapter(0).blocks.filterIsInstance<TextBlock>().map { it.text }
        assertEquals(listOf("CHAPTER I", "First line of a rather long paragraph that keeps going for a while and is paragraph that goes on with more words than fit on one short line.", "Second paragraph."), blocks)
    }

    @Test fun numericFileNamesGiveNoTitle() {
        assertNull(FileTitle.of(File("/proc/self/fd/57")))
        assertNull(FileTitle.of(File("/data/cache/123.txt")))
        assertEquals("My Book", FileTitle.of(File("/sdcard/My_Book.txt")))
        assertTrue(TxtParser.parseText("x").metadata.title == null)
    }
}
