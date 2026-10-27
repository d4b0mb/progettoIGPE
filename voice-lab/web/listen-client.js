// The script of out/listen.html. tts-compare.mjs copies this file INTO listen.html (so that page is one
// self-contained file) together with the data of the listening test. Not an ES module.
(() => {
  const DATA = JSON.parse(document.getElementById("data").textContent);
  const KEY = `voiceLabListen:${DATA.buildId}`; // a changed set of audio files gets a new id, so old ratings are never mixed in

  function load() {
    try {
      const saved = JSON.parse(localStorage.getItem(KEY));
      if (saved && saved.ratings) return saved;
    } catch { /* first visit */ }
    return { ratings: {}, index: 0 };
  }
  const store = load();
  let storageWorks = true;
  function save() {
    try { localStorage.setItem(KEY, JSON.stringify(store)); } catch { storageWorks = false; }
    document.getElementById("saveState").textContent = storageWorks ? "Ratings are saved in this browser as you go." : "Could not save in this browser: press Export ratings before you close the page!";
  }

  const rk = (phraseId, label) => `${phraseId}|${label}`;
  const entry = (phraseId, label) => store.ratings[rk(phraseId, label)] ?? { natural: null, trust: null, comment: "" };
  const isRated = (phraseId, label) => { const e = entry(phraseId, label); return e.natural !== null && e.trust !== null; };

  let index = Math.min(Math.max(0, store.index ?? 0), DATA.items.length - 1);
  let active = "A"; // the player that the keys 1-5 and Space act on

  const $ = (id) => document.getElementById(id);
  const phraseBox = $("phrase");
  const playersBox = $("players");

  function audios() { return [...playersBox.querySelectorAll("audio")]; }
  function audioFor(label) { return playersBox.querySelector(`audio[data-label="${label}"]`); }

  function setActive(label) {
    active = label;
    for (const card of playersBox.querySelectorAll(".player")) card.classList.toggle("active", card.dataset.label === label);
  }

  function setRating(label, kind, value) {
    const item = DATA.items[index];
    const current = entry(item.id, label);
    const next = { ...current, [kind]: current[kind] === value ? null : value }; // pressing the same number again clears it
    store.ratings[rk(item.id, label)] = next;
    save();
    paintRatings(label);
    paintProgress();
  }

  function paintRatings(label) {
    const item = DATA.items[index];
    const e = entry(item.id, label);
    const card = playersBox.querySelector(`.player[data-label="${label}"]`);
    for (const button of card.querySelectorAll("button[data-kind]")) {
      button.classList.toggle("on", e[button.dataset.kind] === Number(button.dataset.value));
    }
    card.classList.toggle("rated", isRated(item.id, label));
  }

  function paintProgress() {
    let players = 0;
    let rated = 0;
    for (const item of DATA.items) for (const p of item.players) { players++; if (isRated(item.id, p.label)) rated++; }
    $("progressText").textContent = `Phrase ${index + 1} of ${DATA.items.length}. ${rated} of ${players} voices rated.`;
    $("progressBar").max = players;
    $("progressBar").value = rated;
  }

  function ratingRow(label, kind, text) {
    const row = document.createElement("div");
    row.className = "rating";
    const caption = document.createElement("span");
    caption.textContent = text;
    row.appendChild(caption);
    for (let value = 1; value <= 5; value++) {
      const button = document.createElement("button");
      button.type = "button";
      button.dataset.kind = kind;
      button.dataset.value = String(value);
      button.textContent = String(value);
      button.addEventListener("click", () => { setActive(label); setRating(label, kind, value); });
      row.appendChild(button);
    }
    return row;
  }

  function render() {
    const item = DATA.items[index];
    store.index = index;
    save();
    phraseBox.textContent = item.text;
    $("lang").textContent = item.lang === "it" ? "Italiano" : "English";
    playersBox.innerHTML = "";
    for (const player of item.players) {
      const card = document.createElement("section");
      card.className = "player";
      card.dataset.label = player.label;
      const title = document.createElement("h2");
      title.textContent = player.label;
      const audio = document.createElement("audio");
      audio.controls = true;
      audio.preload = "auto";
      audio.src = player.src;
      audio.dataset.label = player.label;
      audio.addEventListener("play", () => { for (const other of audios()) if (other !== audio) other.pause(); setActive(player.label); });
      audio.addEventListener("error", () => { card.classList.add("broken"); title.textContent = `${player.label} (audio file could not be loaded)`; });
      const comment = document.createElement("input");
      comment.type = "text";
      comment.placeholder = "Comment (optional)";
      comment.value = entry(item.id, player.label).comment ?? "";
      comment.dataset.comment = player.label;
      comment.addEventListener("focus", () => setActive(player.label));
      comment.addEventListener("input", () => {
        store.ratings[rk(item.id, player.label)] = { ...entry(item.id, player.label), comment: comment.value };
        save();
      });
      card.append(title, audio, ratingRow(player.label, "natural", "Sounds like a person"), ratingRow(player.label, "trust", "I would trust it to read out an allergy warning"), comment);
      playersBox.appendChild(card);
      paintRatings(player.label);
    }
    if (!item.players.some((p) => p.label === active)) active = item.players[0]?.label ?? "A";
    setActive(active);
    $("prev").disabled = index === 0;
    $("next").disabled = index === DATA.items.length - 1;
    paintProgress();
  }

  function go(next) {
    if (next < 0 || next >= DATA.items.length) return;
    for (const a of audios()) a.pause();
    index = next;
    render();
    window.scrollTo(0, 0);
  }

  function goNextUnrated() {
    for (let step = 1; step <= DATA.items.length; step++) {
      const i = (index + step) % DATA.items.length;
      if (DATA.items[i].players.some((p) => !isRated(DATA.items[i].id, p.label))) return go(i);
    }
    $("exportState").textContent = "Every voice is rated. Press Export ratings.";
  }

  async function exportRatings() {
    const mapping = {};
    const ratings = [];
    for (const item of DATA.items) {
      mapping[item.id] = {};
      for (const player of item.players) {
        mapping[item.id][player.label] = player.candidate;
        const e = entry(item.id, player.label);
        ratings.push({ phraseId: item.id, lang: item.lang, label: player.label, natural: e.natural, trust: e.trust, comment: e.comment ?? "" });
      }
    }
    const payload = { exportedAt: new Date().toISOString(), buildId: DATA.buildId, mapping, ratings };
    const text = JSON.stringify(payload, null, 2);
    const link = document.createElement("a");
    link.href = URL.createObjectURL(new Blob([text], { type: "application/json" }));
    link.download = "ratings.json";
    document.body.appendChild(link);
    link.click();
    link.remove();
    let message = "Downloaded ratings.json.";
    if (location.protocol === "http:") {
      try {
        const response = await fetch(`/save?name=${encodeURIComponent("ratings/ratings.json")}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: text });
        if (response.ok) message += " A copy was also saved in voice-lab/out/ratings/ratings.json, which is where score.mjs looks.";
      } catch { /* the page was not opened through serve.mjs: only the download exists */ }
    }
    $("exportState").textContent = `${message} (${ratings.filter((r) => r.natural !== null && r.trust !== null).length} of ${ratings.length} voices rated)`;
  }

  $("prev").addEventListener("click", () => go(index - 1));
  $("next").addEventListener("click", () => go(index + 1));
  $("nextUnrated").addEventListener("click", goNextUnrated);
  $("export").addEventListener("click", exportRatings);

  document.addEventListener("keydown", (event) => {
    const tag = event.target.tagName;
    if (tag === "INPUT" || tag === "TEXTAREA" || event.ctrlKey || event.metaKey || event.altKey) return;
    const item = DATA.items[index];
    const key = event.key.toLowerCase();
    if (key === "arrowleft") { go(index - 1); return; }
    if (key === "arrowright") { go(index + 1); return; }
    if (key === "n") { goNextUnrated(); return; }
    if (key === "m") { event.preventDefault(); playersBox.querySelector(`input[data-comment="${active}"]`)?.focus(); return; }
    const digit = /^Digit([1-5])$/.exec(event.code) ?? /^Numpad([1-5])$/.exec(event.code);
    if (digit) { setRating(active, event.shiftKey ? "trust" : "natural", Number(digit[1])); return; }
    const letter = key.length === 1 ? key.toUpperCase() : "";
    const isPlayerKey = item.players.some((p) => p.label === letter);
    if (isPlayerKey || event.code === "Space") {
      event.preventDefault();
      document.activeElement?.blur?.();
      const audio = audioFor(isPlayerKey ? letter : active);
      if (!audio) return;
      if (audio.paused) audio.play().catch(() => {}); else audio.pause();
    }
  });

  save();
  render();
})();
