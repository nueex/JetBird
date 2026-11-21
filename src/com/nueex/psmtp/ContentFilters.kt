/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import java.net.URI
import java.util.Locale

/** Immutable after publishing to WebView's background request threads. */
internal class ContentFilters private constructor(
    private val domains: Map<String, List<Rule>>,
    private val tokens: Map<String, List<Rule>>,
    private val siteRules: Map<String, List<Rule>>,
    private val cosmetic: Map<String, List<Cosmetic>>,
    private val suffixes: Set<String>
) {
    private class Rule(
        val allow: Boolean, val important: Boolean, val pattern: Regex?, val party: Boolean?,
        val sites: List<String>, val excludedSites: List<String>,
        val targets: List<String>, val excludedTargets: List<String>,
        val types: Set<String>, val excludedTypes: Set<String>
    ) {
        fun matches(url: String, host: String, page: String, thirdParty: Boolean?, type: String): Boolean =
            (party == null || party == thirdParty) &&
                (sites.isEmpty() || sites.any { within(page, it) }) &&
                excludedSites.none { within(page, it) } &&
                (targets.isEmpty() || targets.any { within(host, it) }) &&
                excludedTargets.none { within(host, it) } &&
                (types.isEmpty() || type in types) && type !in excludedTypes &&
                (pattern == null || pattern.containsMatchIn(url))
    }

    private data class Cosmetic(val selector: String, val include: List<String>, val exclude: List<String>, val allow: Boolean)

    fun blocks(url: String, host: String, page: String, type: String): Boolean {
        if (host.isEmpty() || url.length > 8192) return false
        val target = url.lowercase(Locale.ROOT)
        val thirdParty = if (page.isEmpty()) null else site(host) != site(page)
        var priority = 0
        fun match(rule: Rule) {
            if (!rule.matches(target, host, page, thirdParty, type)) return
            val rank = if (rule.allow) 2 else if (rule.important) 3 else 1
            priority = maxOf(priority, rank)
        }
        for (domain in parents(host)) {
            domains[domain].orEmpty().forEach(::match)
            if (priority == 3) return true
        }
        for (domain in parents(page)) {
            siteRules[domain].orEmpty().forEach(::match)
            if (priority == 3) return true
        }
        if (tokens.isNotEmpty()) {
            for (index in 0..target.length - TOKEN) {
                tokens[target.substring(index, index + TOKEN)].orEmpty().forEach(::match)
                if (priority == 3) return true
            }
        }
        return priority == 1
    }

    fun selectors(host: String): List<String> {
        if (host.isEmpty()) return emptyList()
        val hide = LinkedHashSet<String>()
        val show = HashSet<String>()
        for (key in parents(host) + sequenceOf("")) {
            for (rule in cosmetic[key].orEmpty()) {
                if (rule.include.isNotEmpty() && rule.include.none { within(host, it) }) continue
                if (rule.exclude.any { within(host, it) }) continue
                if (rule.allow) show.add(rule.selector) else hide.add(rule.selector)
            }
        }
        return hide.filterNot(show::contains)
    }

    private fun site(host: String): String {
        if (host.isEmpty() || ':' in host || host.all { it.isDigit() || it == '.' }) return host
        val parts = host.split('.')
        var suffix = 1
        for (i in parts.indices) {
            val candidate = parts.subList(i, parts.size).joinToString(".")
            val count = parts.size - i
            if ("!$candidate" in suffixes) { suffix = count - 1; break }
            if (candidate in suffixes) suffix = maxOf(suffix, count)
            if (i > 0 && "*.$candidate" in suffixes) suffix = maxOf(suffix, count + 1)
        }
        return parts.takeLast(suffix + 1).joinToString(".")
    }

    companion object {
        private const val TOKEN = 6
        private val domainSyntax = Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
        private val literal = Regex("[a-z0-9_/%.-]{$TOKEN,}")
        private val requestTypes = setOf("script", "image", "stylesheet", "font", "media", "xmlhttprequest", "subdocument",
            "other", "ping", "document", "popup", "popunder")
        private val typeAliases = mapOf("xhr" to "xmlhttprequest", "frame" to "subdocument", "doc" to "document")
        private val nonCss = Regex("(?i)[{}\\u0000-\\u001f]|@import|url\\(|:(has-text|matches-css|xpath|style|upward|remove)\\(")

        fun compile(lines: Sequence<String>, suffixes: Set<String>): ContentFilters {
            val domains = HashMap<String, MutableList<Rule>>()
            val tokens = HashMap<String, MutableList<Rule>>()
            val siteRules = HashMap<String, MutableList<Rule>>()
            val cosmetic = HashMap<String, MutableList<Cosmetic>>()
            val input = lines.toList()
            fun ruleKey(pattern: String, options: List<String>) = pattern to options.filter { it != "badfilter" }.sorted()
            val disabled = input.asSequence().filter { "badfilter" in it && it.length <= 1024 }.mapNotNull { line ->
                val options = line.substringAfter('$', "").split(',').filter(String::isNotEmpty)
                if ("badfilter" in options) ruleKey(line.substringBefore('$'), options) else null
            }.toHashSet()
            for (line in input) {
                if (line.isEmpty() || line.length > 1024 || line[0] in "![" ) continue
                val cosmeticMarker = if ("#@#" in line) "#@#" else if ("##" in line) "##" else ""
                if (cosmeticMarker.isNotEmpty()) {
                    val selector = line.substringAfter(cosmeticMarker)
                    if (selector.isEmpty() || selector.startsWith("+js(") || nonCss.containsMatchIn(selector)) continue
                    val sources = line.substringBefore(cosmeticMarker).lowercase(Locale.ROOT).split(',').filter(String::isNotEmpty)
                    if (sources.any { !domainSyntax.matches(it.removePrefix("~")) }) continue
                    val sites = sources.filterNot { it.startsWith('~') }
                    val rule = Cosmetic(selector, sites, sources.filter { it.startsWith('~') }.map { it.drop(1) }, cosmeticMarker == "#@#")
                    for (key in sites.ifEmpty { listOf("") }) cosmetic.getOrPut(key) { ArrayList() }.add(rule)
                    continue
                }
                val allow = line.startsWith("@@")
                val body = if (allow) line.drop(2) else line
                if (body.isEmpty()) continue
                val optionText = body.substringAfter('$', "")
                val options = optionText.split(',').filter(String::isNotEmpty)
                if (disabled.isNotEmpty() && ruleKey(line.substringBefore('$'), options) in disabled) continue
                var party: Boolean? = null
                var sites = emptyList<String>()
                var excluded = emptyList<String>()
                var targets = emptyList<String>()
                var excludedTargets = emptyList<String>()
                val types = HashSet<String>()
                val excludedTypes = HashSet<String>()
                var supported = true
                var important = false
                for (option in options) {
                    val kind = option.removePrefix("~")
                    val type = typeAliases[kind] ?: kind
                    when {
                        option == "third-party" || option == "3p" -> party = true
                        option == "~third-party" || option == "first-party" || option == "1p" -> party = false
                        option == "important" -> important = true
                        option.startsWith("domain=") || option.startsWith("from=") || option.startsWith("to=") -> {
                            val all = option.substringAfter('=').lowercase(Locale.ROOT).split('|')
                            if (all.any { !domainSyntax.matches(it.removePrefix("~")) }) supported = false
                            val include = all.filterNot { it.startsWith('~') }
                            val exclude = all.filter { it.startsWith('~') }.map { it.drop(1) }
                            if (option.startsWith("to=")) {
                                targets = include
                                excludedTargets = exclude
                            } else {
                                sites = include
                                excluded = exclude
                            }
                        }
                        type in requestTypes -> if (option.startsWith('~')) excludedTypes.add(type) else types.add(type)
                        else -> supported = false
                    }
                }
                if (!supported) continue
                val raw = body.substringBefore('$').lowercase(Locale.ROOT)
                if (raw.isEmpty() || (raw.startsWith('/') && raw.endsWith('/')) || raw.count { it == '*' } > 6) continue
                val anchored = raw.startsWith("||")
                val base = if (anchored) raw.drop(2).takeWhile { it.isLetterOrDigit() || it in ".-" } else ""
                val domain = base.takeIf { anchored && domainSyntax.matches(it) && '.' in it && raw.getOrNull(it.length+2) in listOf(null, '^', '/', ':', '|', '?') }
                val domainOnly = domain != null && (raw == "||$domain^" || raw == "||$domain")
                val token = if (domain == null) literal.findAll(raw).maxByOrNull { it.value.length }?.value?.take(TOKEN) else null
                val scopedWildcard = raw == "*" && (sites.isNotEmpty() || targets.isNotEmpty()) && types.isNotEmpty()
                if (domain == null && token == null && !scopedWildcard) continue
                val pattern = if (domainOnly || scopedWildcard) null else compilePattern(raw)
                val rule = Rule(allow, important, pattern, party, sites, excluded, targets, excludedTargets, types, excludedTypes)
                when {
                    domain != null -> domains.getOrPut(domain) { ArrayList() }.add(rule)
                    token != null -> tokens.getOrPut(token) { ArrayList() }.add(rule)
                    targets.isNotEmpty() -> targets.forEach { domains.getOrPut(it) { ArrayList() }.add(rule) }
                    else -> sites.forEach { siteRules.getOrPut(it) { ArrayList() }.add(rule) }
                }
            }
            return ContentFilters(domains, tokens, siteRules, cosmetic, suffixes)
        }

        private fun compilePattern(raw: String): Regex {
            val anchored = raw.startsWith("||")
            val pattern = raw.trim('|')
            return Regex(buildString {
                if (anchored) append("^[a-z][a-z0-9+.-]*://(?:[^/:?#]+\\.)?")
                else if (raw.startsWith('|')) append('^')
                var start = 0
                for (index in pattern.indices) {
                    val character = pattern[index]
                    if (character != '*' && character != '^') continue
                    if (index > start) append(Regex.escape(pattern.substring(start, index)))
                    if (character == '*') append(".*")
                    else append("(?:[^a-z0-9_%.\\-]|$)")
                    start = index + 1
                }
                if (pattern.length > start) append(Regex.escape(pattern.substring(start)))
                if (raw.endsWith('|')) append('$')
            })
        }

        fun host(url: String): String = runCatching {
            URI(url).host.orEmpty().trimEnd('.').lowercase(Locale.ROOT)
        }.getOrDefault("")
        fun publicSuffixRule(line: String): String? = runCatching {
            val rule = line.trim()
            val prefix = when {
                rule.startsWith("*.") -> "*."
                rule.startsWith('!') -> "!"
                else -> ""
            }
            prefix + java.net.IDN.toASCII(rule.drop(prefix.length)).lowercase(Locale.ROOT)
        }.getOrNull()
        private fun within(host: String, domain: String) = host == domain || host.endsWith(".$domain")
        private fun parents(host: String) = generateSequence(host) { value ->
            value.substringAfter('.', "").takeIf(String::isNotEmpty)
        }
    }
}
