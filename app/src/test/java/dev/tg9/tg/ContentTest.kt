package dev.tg9.tg

import dev.tg9.Fixtures
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentTest {
    @Test
    fun textMessageHasNoLabel() {
        assertNull(Content.label(Fixtures.text("hi")))
        assertEquals("hi", Content.summary(Fixtures.text("hi")))
    }

    @Test
    fun summaryFlattensNewlines() {
        assertEquals("one two", Content.summary(Fixtures.text("one\ntwo")))
    }

    @Test
    fun photoWithAndWithoutCaption() {
        assertEquals("[Photo]", Content.summary(Fixtures.photo()))
        assertEquals("[Photo] at the lake", Content.summary(Fixtures.photo("at the lake")))
        assertEquals("[Photo]\nat the lake", Content.body(Fixtures.photo("at the lake")))
    }

    @Test
    fun voiceShowsDuration() {
        assertEquals("[Voice 1:05]", Content.summary(Fixtures.voice(65)))
    }

    @Test
    fun durations() {
        assertEquals("0:00", Content.duration(0))
        assertEquals("0:07", Content.duration(7))
        assertEquals("12:34", Content.duration(754))
        assertEquals("1:02:03", Content.duration(3723))
    }

    @Test
    fun linksFromUrlsAndTextLinks() {
        val body = "see example.com and this"
        val content = TdApi.MessageText().apply {
            text = TdApi.FormattedText(
                body,
                arrayOf(
                    TdApi.TextEntity(4, 11, TdApi.TextEntityTypeUrl()),
                    TdApi.TextEntity(20, 4, TdApi.TextEntityTypeTextUrl("https://tdlib.org/x")),
                    TdApi.TextEntity(4, 11, TdApi.TextEntityTypeUrl()),
                ),
            )
        }
        assertEquals(listOf("https://example.com", "https://tdlib.org/x"), Content.links(content))
        assertEquals(emptyList<String>(), Content.links(Fixtures.text("no links")))
    }

    @Test
    fun contactLocationAndPhoneNumbers() {
        val contact = TdApi.MessageContact().apply {
            contact = TdApi.Contact().apply { firstName = "Anna"; lastName = ""; phoneNumber = "37255551234" }
        }
        assertEquals("[Contact: Anna, 37255551234]", Content.label(contact))
        assertEquals(listOf("+37255551234"), Content.phoneNumbers(contact))

        val location = TdApi.MessageLocation().apply { location = TdApi.Location(59.437, 24.7536, 0.0) }
        assertEquals("[Location 59.43700, 24.75360]", Content.label(location))
        assertEquals(59.437, Content.location(location)!!.latitude, 0.0)

        val call = TdApi.MessageText().apply {
            text = TdApi.FormattedText("call +3725555", arrayOf(TdApi.TextEntity(5, 8, TdApi.TextEntityTypePhoneNumber())))
        }
        assertEquals(listOf("+3725555"), Content.phoneNumbers(call))
    }

    @Test
    fun pollListsItsOptions() {
        fun option(text: String, percent: Int) = TdApi.PollOption().apply {
            this.text = TdApi.FormattedText(text, emptyArray())
            votePercentage = percent
        }
        val poll = TdApi.MessagePoll().apply {
            poll = TdApi.Poll().apply {
                question = TdApi.FormattedText("Lunch?", emptyArray())
                options = arrayOf(option("Yes", 75), option("No", 25))
                totalVoterCount = 4
                canSeeResults = true
            }
        }
        assertEquals("[Poll: Lunch?]\n• Yes — 75%\n• No — 25%\n4 votes", Content.body(poll))
    }

    @Test
    fun fileSizes() {
        assertEquals("900 B", Content.fileSize(900))
        assertEquals("820 KB", Content.fileSize(820 * 1024))
        assertEquals("4.1 MB", Content.fileSize(4_300_000))
    }

    @Test
    fun bestPhotoSizeIsTheLargestThatFits() {
        val photo = Fixtures.photo("", 90, 320, 800, 1280, 2560).photo
        assertEquals(800, Content.bestPhotoSize(photo, 1000)!!.width)
        assertEquals(1280, Content.bestPhotoSize(photo, 1280)!!.width)
        // Nothing fits: fall back to the smallest rather than to nothing.
        assertEquals(90, Content.bestPhotoSize(photo, 50)!!.width)
    }
}
