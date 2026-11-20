package com.demon.nothingassistant.ai

import kotlin.random.Random

/**
 * Casual conversation without an AI. Decides whether a sentence is chatter (never worth a web search)
 * and answers it using what was said just before, so small talk feels like a conversation, not a lookup.
 */
object Banter {
    private val I = RegexOption.IGNORE_CASE
    private val last = HashMap<String, String>()

    private fun pick(key: String, list: List<String>): String {
        var c = list.random()
        repeat(4) { if (c == last[key] && list.size > 1) c = list.random() }
        last[key] = c
        return c
    }

    private val fillers = Regex("\\b(the hell|the heck|the fuck|the hell|bro|bruh|yaar|dude|man|buddy|boss|please|pls|just|actually|basically|like|literally|btw|hmm+|umm+|uh+|well|so)\\b", I)

    /** Lowercase, no punctuation or filler words. */
    fun norm(raw: String): String = raw.lowercase().replace("’", "").replace("'", "")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(fillers, " ").replace(Regex("\\s+"), " ").trim()

    private val factual = Regex("\\b(capital|president|prime minister|population|price|cost|weather|score|news|meaning of|define|definition|how to|recipe|who is|who was|what is|whats|when did|where is|how many|how much|how old|how far|how tall|how long|distance|translate|convert|latest|release date|net worth)\\b", I)
    private val aboutUs = Regex("\\b(you|your|youre|u|ur|yourself|assistant)\\b", I)
    private val personal = Regex("^(i|im|ive|ill|id|my|me|we|lol|lmao|haha+|hehe+|oh|wow|ok|okay|yeah|yes|yep|no|nope|nah|thanks|thank|thx|bye|really|why|dont|cant|wont|stop|shut|wait|hmm|huh|what|nothing|nice|cool|damn|omg|sure|maybe|idk|fine|good|great|bad|sad|same|exactly|true|right|wrong|sorry|never|always|do you|are you|did you|can you|will you|would you|have you)\\b", I)

    /** True for chatter, feelings, reactions and talk about the assistant itself. */
    fun isCasual(raw: String): Boolean {
        val t = norm(raw)
        if (t.isEmpty()) return false
        val words = t.split(' ')
        if (factual.containsMatchIn(t) && !aboutUs.containsMatchIn(t)) return false
        if (words.size <= 3 && !Regex("^(who|when|where|which|define|meaning)\\b").containsMatchIn(t)) return true
        if (aboutUs.containsMatchIn(t) && words.size <= 12) return true
        if (personal.containsMatchIn(t) && words.size <= 10) return true
        return false
    }

    /** [lastReply] is what the assistant said last, [lastUser] what the person said before this. */
    fun reply(raw: String, lastReply: String?, lastUser: String?, justActed: Boolean): String? {
        val t = norm(raw)
        if (t.isEmpty()) return null

        if (Regex("^(wtf|what are you doing|what is wrong with you|whats wrong with you|why did you do that|why are you doing that|what did you do|why would you do that|what was that|stop doing that|why did you open that|you did it wrong|that wasnt what i asked)$").matches(t) ||
            Regex("what are you doing|why are you doing this|why did you do").containsMatchIn(t) && Regex("hell|heck|wtf").containsMatchIn(raw.lowercase())) {
            return if (justActed) pick("wtf", listOf(
                "Sorry, I got that wrong. Tell me again what you wanted and I'll do just that.",
                "My mistake. Say it once more, a bit simpler, and I'll fix it.",
                "Oops. I misunderstood. What did you want me to do?"))
            else pick("wtf2", listOf(
                "Just listening and trying to keep up with you. What do you need?",
                "Sorry if that was confusing. Tell me what you'd like and I'll do it.",
                "Trying my best! Tell me what you want and I'll sort it."))
        }

        if (Regex("^(im |i am |all |doing )?(good|fine|great|okay|ok|alright|well|not bad|doing good|doing well|doing great|pretty good|all good|mast|badhiya|theek|thik|theek hai|bas badhiya)( thanks| thank you| and you| you)?$").matches(t)) {
            return pick("good", listOf("Glad to hear it. What can I do for you?", "Nice. Anything I can help with?", "Good to hear. I'm here if you need anything.", "Great! Want some music, or are we just chilling?"))
        }
        if (Regex("^(nothing|nothing much|not much|nothing special|just chilling|chilling|relaxing|just relaxing|just sitting|just bored|same old|bas aise hi|kuch nahi)$").matches(t)) {
            return pick("nothing", listOf("Sounds relaxing. Want some music, a joke, or a fun fact?", "Fair enough. I can tell a story if you like.", "Chill mode, nice. Say the word if you want anything."))
        }
        if (Regex("^(and you|and u|what about you|how about you|you tell me|tum batao|aap batao)$").matches(t)) {
            return pick("andyou", listOf("I'm doing great, thanks. Ready to help whenever you are.", "All good here. Just happy to be chatting with you."))
        }
        if (Regex("^(lol|lmao|rofl|haha+|hehe+|hahaha+|funny|that was funny|good one|that was good|nice one|ha ha|lolol)$").matches(t)) {
            return pick("laugh", listOf("Glad that got a laugh. Want another?", "Ha, I try. Want one more?", "Happy to entertain. Say another one for more."))
        }
        if (Regex("^(thanks|thank you|thx|thank u|thanks a lot|thanks bro|many thanks|dhanyavaad|shukriya|thanks man|thank you so much)$").matches(t)) {
            return pick("thanks", listOf("Anytime.", "You're welcome.", "Happy to help.", "Any time. Just call me."))
        }
        if (Regex("^(bye|goodbye|see you|see ya|cya|later|talk later|good bye|alvida|chalo bye|ok bye|okay bye|bye bye)$").matches(t)) {
            return pick("bye", listOf("Bye! Talk soon.", "See you later.", "Take care. I'm here when you need me."))
        }
        if (Regex("^(why|why so|but why|how come|what do you mean|what does that mean|meaning|huh|what|come again|explain|explain that|what do u mean|matlab|kya matlab|kyun)$").matches(t) && lastReply != null) {
            val short = lastReply.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ").take(220)
            return pick("why", listOf("I meant: $short", "Let me put it simply. $short", "What I said was: $short"))
        }
        if (Regex("^(really|seriously|are you sure|you sure|for real|sach mein|sachi|pakka|is that true|is it true|r u sure|sure)$").matches(t) && lastReply != null) {
            return pick("sure", listOf("As far as I know, yes. Ask me to search the web if you want it checked.", "I think so, but I can double check online if you like. Just ask.", "I'm fairly sure. Want me to look it up to be certain?"))
        }
        if (Regex("(you are|youre|that is|thats|this is|its|you got it|you got that) (so |very |really |completely |totally )?(wrong|incorrect|not right|not correct|false|a lie)|wrong answer|not what i asked|that isnt right|galat").containsMatchIn(t)) {
            return pick("wrong", listOf("Sorry about that. Tell me what's right, or ask again and I'll look it up.", "My bad. Ask me again a bit differently and I'll try to do better.", "Thanks for telling me. Say it again and I'll give it another go."))
        }
        if (Regex("^(shut up|be quiet|quiet|stop talking|silence|chup|chup raho|enough|stop it|okay stop|ok stop)$").matches(t)) {
            return pick("quiet", listOf("Okay, quiet mode.", "Alright, I'll hush.", "Got it. Silent."))
        }
        if (Regex("^(yes|yeah|yep|yup|sure|ya|haan|han|ji|ok sure)$").matches(t)) return pick("yes", listOf("Okay. What would you like to do?", "Alright. Tell me what you need.", "Got it. What's next?"))
        if (Regex("^(no|nope|nah|nahi|na|no thanks|not now|no thank you)$").matches(t)) return pick("no", listOf("Okay, no problem.", "Alright. Let me know if you need anything.", "Sure. I'm here if you change your mind."))
        if (Regex("^(idk|i dont know|dont know|no idea|not sure|i am not sure|im not sure|pata nahi|maybe|i guess|dunno)$").matches(t)) {
            return pick("idk", listOf("No problem. Want ideas? I can play music, tell a joke, or read the news.", "That's okay. Take your time. I can suggest something if you like.", "Fair. Want me to pick something fun?"))
        }
        if (Regex("^(ok|okay|hmm|hm|oh|ohh|oh okay|i see|alright then|ah|aha|oh ok|achha|accha|acha|oh nice|wow|oh wow|damn|omg|oh my god)$").matches(t)) {
            return pick("ack", listOf("Mm-hm.", "Right.", "Anything else on your mind?", "I'm listening."))
        }

        if (Regex("^(are you|r u|you) (angry|mad|upset|sad|hurt|offended)").containsMatchIn(t)) return "Not at all. I don't hold grudges. What's up?"
        if (Regex("^(are you|r u) (happy|ok|okay|fine|alright|good|there|listening|awake)").containsMatchIn(t)) return pick("areyou", listOf("Yep, all good and listening.", "I'm fine, thanks for checking on me.", "Always here."))
        if (Regex("are you (smart|intelligent|clever)|how smart are you").containsMatchIn(t)) return "Getting smarter every update. Not Einstein yet, but I try."
        if (Regex("why are you (slow|so slow|lagging|taking so long|dumb|stupid|not working)|you are slow|youre slow").containsMatchIn(t)) {
            return pick("slow", listOf("Sorry for the wait. Some things take me a moment, especially when I'm using the screen. I'll try to be quicker.", "Fair point. I'm still learning to be faster. Try again and tell me if it's still slow."))
        }
        if (Regex("^(tell me about yourself|introduce yourself|describe yourself)$").matches(t)) {
            return "I'm Nothing Assistant, a voice assistant living on your phone. I can open apps, change settings, control the screen, send messages, and just chat. What would you like?"
        }
        Regex("^do you (?:like|love|enjoy|hate|know|watch|play|listen to) (.{2,40})$").find(t)?.let {
            val o = it.groupValues[1].trim()
            return pick("dyl", listOf("I don't have feelings quite like yours, but $o sounds interesting. What do you like about it?", "Hard to say as an assistant, but I'm curious about $o. Tell me more?"))
        }
        Regex("^(?:i|i really|i just) (?:like|love|enjoy|adore|prefer) (.{2,40})$").find(t)?.let {
            val o = it.groupValues[1].trim().removePrefix("to ").removePrefix("the ")
            return pick("ilike", listOf("Nice, $o sounds good. Want me to search or play something about it?", "That's cool. What's your favourite thing about $o?", "Good taste! Tell me more about $o."))
        }
        Regex("^(?:i|i really|i just) (?:hate|dislike|cant stand|am tired of|am sick of) (.{2,40})$").find(t)?.let {
            val o = it.groupValues[1].trim()
            return pick("ihate", listOf("Ugh, $o can be annoying. What bugs you most about it?", "Fair. Not everyone likes $o. Want to talk about it?"))
        }
        Regex("^(?:i dont|i do not|i cant|i cannot|i dont really) (?:understand|get|follow) (.{0,40})$").find(t)?.let {
            val o = it.groupValues[1].trim()
            return if (o.contains("joke")) "Jokes can be tricky. Want me to explain the last one, or tell you a simpler one?"
            else if (o.isBlank()) "Okay, let me try again more simply. What part is confusing?"
            else pick("dontget", listOf("Let's figure out $o together. What part is confusing?", "No worries. Ask me about $o and I'll keep it simple."))
        }
        if (Regex("^(explain|explain it|explain the joke|explain that joke|explain this joke|what is the joke|where is the joke|i dont get it|i dont get the joke|got it|dont get it)$").matches(t) && lastReply != null) {
            return if (lastUser != null && Regex("joke|funny").containsMatchIn(lastUser.lowercase()) || lastReply.contains("?") ) "Jokes usually set up one expectation, then twist it. If it's a pun, it plays on a word with two meanings. Want a simpler one?"
            else "Sure. In short: " + lastReply.split(Regex("(?<=[.!?])\\s+")).first().take(200)
        }
        if (Regex("^(i want|i need|i wanna|i would like) (to )?(.{2,40})$").containsMatchIn(t) && !Regex("\\b(open|call|play|search|turn|set|send|install|remind)\\b").containsMatchIn(t)) {
            return pick("iwant", listOf("Tell me what you'd like me to do and I'll try. For example, open an app, play a song or set a timer.", "Sure. What exactly should I do?"))
        }
        Regex("^(?:i am|im|i feel|i m) (?:so |very |really |kind of |a bit |a little )?(.{2,30})$").find(t)?.let {
            val s = it.groupValues[1].trim()
            if (!Regex("\\b(going|gonna|trying|looking|at|in|on|from|with|using|about|not|here|back)\\b").containsMatchIn(s))
                return pick("imstate", listOf("Got it, $s. Want to talk about it, or should I do something for you?", "I hear you. Tell me more if you like.", "Okay. I'm here either way."))
        }
        if (Regex("^(you are|youre|u r|u are) (.{2,25})$").matches(t)) return pick("youare", listOf("Thanks for the honest take. I'll keep improving.", "Noted. Tell me how I can be better.", "I'll take that on board."))
        return null
    }

    /** When it's chatter but nothing specific matches: keep the conversation going instead of searching the web. */
    fun fallback(raw: String): String {
        val t = norm(raw)
        return if (t.endsWith("?") || Regex("^(why|how|what|do|did|can|will|would|are|is)\\b").containsMatchIn(t))
            pick("cq", listOf("Good question. I don't have a solid answer for that. Want me to search the web for it?", "Hmm, not sure about that one. Say search, and the topic, and I'll look it up.", "I'm not certain. Ask me to search and I'll find out."))
        else pick("cf", listOf("I hear you. Tell me more.", "Interesting. Go on, I'm listening.", "Okay. Want to tell me what you'd like to do?", "Mm. What's on your mind?"))
    }
}
