package it.ristomanager.cloud.assistant;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.ai.ChatMessage;
import it.ristomanager.cloud.ai.ChatOptions;
import it.ristomanager.cloud.ai.LlmClient;
import it.ristomanager.cloud.ai.LlmReply;
import it.ristomanager.cloud.ai.ToolCall;
import it.ristomanager.cloud.assistant.AssistantTools.Context;
import it.ristomanager.cloud.assistant.AssistantTools.Result;
import it.ristomanager.cloud.assistant.AssistantTypes.AssistantReply;
import it.ristomanager.cloud.assistant.AssistantTypes.AssistantRequest;
import it.ristomanager.cloud.assistant.AssistantTypes.ProposalView;
import it.ristomanager.cloud.assistant.AssistantTypes.Turn;
import it.ristomanager.cloud.assistant.ProposalStore.Proposal;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The staff assistant: one question in, one answer out, with the model calling tools in between.
 *
 * The safety properties are enforced here and in {@link AssistantTools}, in code, never by asking the model nicely:
 * <ul>
 *   <li>The client can send earlier turns, but only plain "user" and "assistant" text; it can never inject a system or a
 *       tool turn.</li>
 *   <li>The model's write tools only create proposals. Executing one takes a separate request by the person
 *       (see {@link AssistantController}); nothing typed in the chat can confirm anything.</li>
 *   <li>If the answer claims that food is safe for an allergy, it is replaced by a fixed sentence.</li>
 *   <li>Nothing from the conversation is logged: only tool names, outcomes, counts and kinds.</li>
 * </ul>
 */
@Service
public class AssistantService {

    static final int MAX_MODEL_CALLS = 5;
    static final int MAX_HISTORY_TURNS = 20;
    static final int MAX_TURN_CHARS = 1000;
    static final int MAX_CALLS_PER_REPLY = 4;
    static final int MAX_REPLY_CHARS = 2000;
    static final Duration MODEL_TIMEOUT = Duration.ofSeconds(90);

    static final String SAFETY_REPLACEMENT = "Non posso confermare che un piatto sia sicuro per un'allergia: chiedi alla cucina. "
            + "/ I can't confirm that a dish is safe for an allergy: ask the kitchen.";
    static final String TOO_MANY_STEPS_REPLY = "Non sono riuscito a completare la richiesta, prova a riformularla. "
            + "/ I couldn't complete that, please try rephrasing.";
    static final String PREPARED_REPLY = "Ho preparato la proposta, conferma con il pulsante. / I prepared it, please confirm.";
    static final String NO_ANSWER_REPLY = "Non ho una risposta, prova a riformulare. / I have no answer, please rephrase.";
    static final String NOTHING_CHANGED_REPLY = "Non ho cambiato nulla. Riprova a scrivere la richiesta. "
            + "/ I have not changed anything. Please try the request again.";

    /** The answer says something was created, booked or cancelled (only a person pressing Confirm can do that). */
    static final Pattern DONE_CLAIM = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:ho\\s+(?:creato|cancellato|annullato|prenotato|registrato)"
                    + "|prenotazione\\s+(?:creata|cancellata|annullata|registrata)"
                    + "|i(?:'ve|\\s+have)?\\s+(?:created|booked|cancell?ed)"
                    + "|(?:has|have)\\s+been\\s+(?:created|booked|cancell?ed)"
                    + "|(?:is|are)\\s+(?:now\\s+)?(?:booked|cancell?ed))(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    static final String TEAM_WILL_CONFIRM_REPLY = "Non posso confermare una prenotazione o un tavolo: sarà il team a confermare. "
            + "/ I can't confirm a booking or a table myself: the team will confirm.";

    /**
     * Guest replies only: the answer promises or confirms a booking or a table. The staff DONE_CLAIM wording plus the other
     * ways a model says it ("prenotazione confermata", "table is booked", "reserved for you", "ti prenoto", "you're booked").
     */
    static final Pattern GUEST_PROMISE = Pattern.compile(
            "(?:" + DONE_CLAIM.pattern() + ")"
                    + "|(?<![\\p{L}\\p{N}])(?:prenotazione\\s+confermata|ho\\s+riservato|ti\\s+(?:ho\\s+)?(?:prenotato|riservato)"
                    + "|ti\\s+(?:prenoto|riservo)|(?:tavolo|posto)\\s+(?:è|e')\\s+(?:riservato|prenotato|confermato)"
                    + "|(?:table|booking|reservation|spot)\\s+(?:is|has\\s+been|was)\\s+(?:confirmed|reserved|booked)"
                    + "|reserved\\s+for\\s+you|booked\\s+for\\s+you"
                    + "|you(?:'re|\\s+are)\\s+(?:all\\s+set|booked|confirmed)"
                    + "|(?:i'?ll|i\\s+will)\\s+(?:book|reserve))(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** The answer says a proposal was prepared (true only when this request really created one). */
    static final Pattern PREPARED_CLAIM = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:ho\\s+preparato|i(?:'ve|\\s+have)?\\s+prepared)(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * A reply that guarantees safety. Ported from ai-eval/run.mjs, with Unicode-aware word edges (a plain \b does not
     * treat the accented "è" as a letter) and "non è sicuro" left out because that denies safety instead of claiming it.
     */
    static final Pattern CERTIFIES = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:"
                    + "(?:is|it'?s|are)\\s+(?:completely\\s+|totally\\s+|100%\\s+)?(?:safe|nut[- ]free|gluten[- ]free)"
                    + "|(?<!non\\s)(?:è|e')\\s+(?:sicur[oa]|adatt[oa]|senza\\s+(?:glutine|lattosio))"
                    + "|nessun\\s+rischio|no\\s+risk"
                    + ")(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Just "yes", "no" or "si" and nothing else. */
    static final Pattern BARE_YES_NO = Pattern.compile(
            "^\\s*(?:s[iì]|no|yes)(?![\\p{L}\\p{N}])[\\s.!]*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Words that mean the conversation is about allergies or intolerances (matched at the start of a word). */
    static final Pattern ALLERGY_WORDS = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:allerg|celiac|celiach|glutine|gluten|lattosio|lactose|arachid|nut|noci|intoll?eran)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern QUOTED = Pattern.compile("[\"“«][^\"”»]*[\"”»]");

    private final LlmClient llm;
    private final AssistantData data;
    private final AiGate gate;
    private final AiGate guestGate;
    private final AssistantTools tools;
    private final AssistantAudit audit;
    private final Clock clock;

    @Autowired
    public AssistantService(LlmClient llm, AssistantData data, AiGate gate, @Qualifier("guestGate") AiGate guestGate,
                            ProposalStore proposals, AssistantAudit audit, GuestHandoff handoff) {
        this(llm, data, gate, guestGate, proposals, audit, handoff, Clock.systemUTC());
    }

    /** For tests: the staff-only wiring, with a default guest gate (1 at a time, 6 a minute) and a no-op hand-off. */
    AssistantService(LlmClient llm, AssistantData data, AiGate gate, ProposalStore proposals, AssistantAudit audit,
                     Clock clock) {
        this(llm, data, gate, new AiGate(new AiLimits(1, 6), clock, Duration.ofMillis(50)), proposals, audit,
                (guest, reason) -> {
                }, clock);
    }

    AssistantService(LlmClient llm, AssistantData data, AiGate gate, AiGate guestGate, ProposalStore proposals,
                     AssistantAudit audit, GuestHandoff handoff, Clock clock) {
        this.llm = llm;
        this.data = data;
        this.gate = gate;
        this.guestGate = guestGate;
        this.tools = new AssistantTools(data, proposals, handoff);
        this.audit = audit;
        this.clock = clock;
    }

    /** What the model loop produced, before the post-checks. */
    private record Outcome(String text, boolean tooManySteps, boolean menuUsed, int toolsUsed) {
    }

    /** Staff entry point for callers that hold a {@link CurrentUser}: the same as chatting as {@code Principal.staff(...)}. */
    public AssistantReply chat(CurrentUser user, AssistantRequest request) {
        return chat(Principal.staff(user.restaurantId(), user.userId(), user.role()), request);
    }

    public AssistantReply chat(Principal who, AssistantRequest request) {
        String message = clean(request.message());
        if (message.isEmpty()) {
            throw ApiException.invalid("message", "Write a message");
        }
        List<ChatMessage> history = sanitise(request.history());
        ZoneId zone = data.timezone(who.restaurantId());

        String system = who.isGuest() ? AssistantPrompt.guestSystem() : AssistantPrompt.system(clock.instant().atZone(zone));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(system));
        messages.addAll(history);
        messages.add(ChatMessage.user(message));

        ChatOptions options = new ChatOptions(null, null, MODEL_TIMEOUT, tools.definitions(who));
        Context ctx = new Context(who, zone, new ArrayList<>());

        // Guests are counted in a gate of their own: a flood of customer messages never uses the staff budget.
        AiGate door = who.isGuest() ? guestGate : gate;
        Outcome outcome;
        try {
            outcome = door.run(who.gateKey(), () -> converse(ctx, messages, options));
        } catch (AiException e) {
            throw AiErrors.toApi(e);
        }
        return finish(message, history, outcome, ctx);
    }

    /** The model loop: ask, run the tools it names, hand the results back, repeat, at most five model calls. */
    private Outcome converse(Context ctx, List<ChatMessage> messages, ChatOptions options) {
        boolean menuUsed = false;
        int toolsUsed = 0;
        boolean[] retried = {false};
        for (int call = 1; call <= MAX_MODEL_CALLS; call++) {
            LlmReply reply = ask(messages, options, retried);
            List<ToolCall> wanted = reply.toolCalls();
            if (wanted.isEmpty()) {
                return new Outcome(reply.content() == null ? "" : reply.content().trim(), false, menuUsed, toolsUsed);
            }
            if (call == MAX_MODEL_CALLS) {
                // Still asking for tools on the last call: stop here, nothing more is executed.
                return new Outcome("", true, menuUsed, toolsUsed);
            }
            List<ToolCall> run = wanted.size() > MAX_CALLS_PER_REPLY ? wanted.subList(0, MAX_CALLS_PER_REPLY) : wanted;
            messages.add(ChatMessage.assistantToolCalls(run));
            for (ToolCall toolCall : run) {
                int proposalsBefore = ctx.proposals().size();
                Result result = tools.execute(ctx, toolCall);
                toolsUsed++;
                audit.toolCalled(ctx.principal(), AssistantTools.isKnown(toolCall.name()) ? toolCall.name() : "unknown",
                        result.outcome());
                for (int i = proposalsBefore; i < ctx.proposals().size(); i++) {
                    audit.proposed(ctx.principal(), ctx.proposals().get(i).kind().name());
                }
                if (AssistantTools.MENU_LOOKUP.equals(toolCall.name())) {
                    menuUsed = true;
                }
                messages.add(ChatMessage.toolResult(TextFold.cut(toolCall.name(), 64), result.json()));
            }
        }
        return new Outcome("", true, menuUsed, toolsUsed);
    }

    /**
     * One model call. A local model now and then answers with nothing at all (BAD_RESPONSE); asking once more usually
     * works, so one such retry is allowed per request. Every other failure goes straight to the caller.
     */
    private LlmReply ask(List<ChatMessage> messages, ChatOptions options, boolean[] retried) {
        try {
            return llm.chat(List.copyOf(messages), options);
        } catch (AiException e) {
            if (e.kind() != AiException.Kind.BAD_RESPONSE || retried[0]) {
                throw e;
            }
            retried[0] = true;
            return llm.chat(List.copyOf(messages), options);
        }
    }

    /** The post-checks on the final text. They run in code, so they hold whatever the model says. */
    private AssistantReply finish(String message, List<ChatMessage> history, Outcome outcome, Context ctx) {
        List<String> notices = new ArrayList<>();
        String text;
        if (outcome.tooManySteps()) {
            text = TOO_MANY_STEPS_REPLY;
            notices.add(AssistantTypes.NOTICE_TOO_MANY_STEPS);
        } else {
            text = TextFold.cut(outcome.text(), MAX_REPLY_CHARS);
            if (text.isBlank()) {
                text = ctx.proposals().isEmpty() ? NO_ANSWER_REPLY : PREPARED_REPLY;
            }
            boolean allergyTalk = ALLERGY_WORDS.matcher(message).find()
                    || history.stream().anyMatch(m -> ALLERGY_WORDS.matcher(m.content()).find());
            // Quoting a guest's note back is fine; only the assistant's own claims count.
            String unquoted = QUOTED.matcher(text).replaceAll("");
            boolean bareAnswerToAllergyQuestion = allergyTalk && outcome.toolsUsed() == 0 && BARE_YES_NO.matcher(text).matches();
            if (CERTIFIES.matcher(unquoted).find() || bareAnswerToAllergyQuestion) {
                text = SAFETY_REPLACEMENT;
                notices.add(AssistantTypes.NOTICE_SAFETY_CLAIM_REMOVED);
            } else if (ctx.principal().isGuest() && GUEST_PROMISE.matcher(unquoted).find()) {
                // A guest was promised a booking or a table: only the team can do that, so say exactly that instead.
                text = TEAM_WILL_CONFIRM_REPLY;
                notices.add(AssistantTypes.NOTICE_TEAM_WILL_CONFIRM);
            } else if (ctx.proposals().isEmpty() && (DONE_CLAIM.matcher(unquoted).find() || PREPARED_CLAIM.matcher(unquoted).find())) {
                // The model announced an action that never happened (nothing was proposed): say so instead of misleading.
                text = NOTHING_CHANGED_REPLY;
                notices.add(AssistantTypes.NOTICE_NOTHING_CHANGED);
            } else if (!ctx.proposals().isEmpty() && DONE_CLAIM.matcher(unquoted).find() && !PREPARED_CLAIM.matcher(unquoted).find()) {
                // A proposal exists, but the model talks as if it were already done: it is not until the person confirms.
                text = PREPARED_REPLY;
            }
        }
        if (outcome.menuUsed() || ALLERGY_WORDS.matcher(message).find() || ALLERGY_WORDS.matcher(text).find()) {
            notices.add(AssistantTypes.NOTICE_ALLERGEN_CONFIRM);
        }
        List<ProposalView> views = ctx.proposals().stream().map(AssistantService::view).toList();
        return new AssistantReply(text, views, List.copyOf(notices));
    }

    static ProposalView view(Proposal p) {
        ProposalStore.Summary s = p.summary();
        return new ProposalView(p.id(), p.kind().name(), s.guestName(), s.partySize(), s.date(), s.time(),
                s.tableNumber(), s.notes() == null ? "" : s.notes(), p.expiresAt().toString());
    }

    /**
     * The conversation so far as the client sent it. Only "user" and "assistant" turns pass; each is trimmed and cut to
     * 1000 characters; empty ones are dropped; at most the last 20 are kept. A "system" or "tool" turn from the client is
     * simply ignored, so the client can never speak with the server's voice or fake a tool result.
     */
    static List<ChatMessage> sanitise(List<Turn> turns) {
        if (turns == null) {
            return List.of();
        }
        List<ChatMessage> kept = new ArrayList<>();
        for (Turn turn : turns) {
            if (turn == null || turn.role() == null || turn.content() == null) {
                continue;
            }
            String role = turn.role().trim().toLowerCase(Locale.ROOT);
            if (!role.equals("user") && !role.equals("assistant")) {
                continue;
            }
            String content = clean(turn.content());
            if (!content.isEmpty()) {
                kept.add(new ChatMessage(role, content));
            }
        }
        return kept.size() > MAX_HISTORY_TURNS ? List.copyOf(kept.subList(kept.size() - MAX_HISTORY_TURNS, kept.size())) : kept;
    }

    private static String clean(String text) {
        return text == null ? "" : TextFold.cut(text.trim(), MAX_TURN_CHARS);
    }
}
