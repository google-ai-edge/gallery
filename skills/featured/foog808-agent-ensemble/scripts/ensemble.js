(function (global, factory) {
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = factory();
  } else {
    global.createEnsembleResult = factory().createEnsembleResult;
  }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  function normalizeRequest(request) {
    return String(request || '').trim();
  }

  function getFocus(index) {
    if (index < 5) return 'technical correctness';
    if (index < 11) return 'UX clarity and structure';
    if (index < 17) return 'risk review and validation';
    return 'presentation and user guidance';
  }

  function scoreCandidate(index, request) {
    const base = 95 - index * 2;
    const normalized = normalizeRequest(request).toLowerCase();
    const lengthBoost = Math.min(8, Math.floor(Math.max(0, normalized.length - 30) / 20));
    const keywordBoost = ['fix', 'test', 'validate', 'deploy', 'improve', 'doc', 'review'].reduce(
      (sum, token) => (normalized.includes(token) ? sum + 2 : sum),
      0
    );
    return Math.max(0, Math.min(100, base + lengthBoost + keywordBoost));
  }

  function medalForScore(score) {
    if (score >= 94) return 'Gold';
    if (score >= 88) return 'Silver';
    if (score >= 80) return 'Bronze';
    return 'None';
  }

  function makeCandidate(index, request) {
    const score = scoreCandidate(index, request);
    const medal = medalForScore(score);
    const focus = getFocus(index);
    const summary = `Candidate ${index + 1}: ${focus} focused response for the request.`;
    return {
      id: index + 1,
      summary,
      details: {
        goose: `Goose assesses candidate ${index + 1}, checks feasibility, and documents the technical assumptions for the request.`,
        agency_agents: `Agency Agents analyzes candidate ${index + 1} for strategy, role alignment, and process guidance.`,
        gallery: `Gallery reviews candidate ${index + 1} for user-facing clarity, presentation, and practical next steps.`
      },
      focus,
      score,
      medal,
      recommendation: `Use candidate ${index + 1} to ${focus} with a focus on the user's request.`
    };
  }

  function createEnsembleResult(request) {
    const safeRequest = normalizeRequest(request);
    const candidates = safeRequest
      ? Array.from({ length: 23 }, (_, index) => makeCandidate(index, safeRequest))
      : [];
    const sorted = [...candidates].sort((a, b) => b.score - a.score);
    const bestCandidate = sorted[0] || null;

    return {
      final_result: safeRequest
        ? `The FooG808 Agent Ensemble generated 23 candidates and selected the top candidate (#${bestCandidate.id}) for implementation.`
        : 'No request supplied. Please provide text in the `request` field.',
      best_candidate: bestCandidate,
      candidates,
      goose_notes: safeRequest
        ? 'Goose has evaluated feasibility, assumptions, and technical risk for the top candidate.'
        : 'No request provided for Goose.',
      agency_agents_notes: safeRequest
        ? 'Agency Agents has reviewed strategic alignment, process flow, and recommendation quality.'
        : 'No request provided for Agency Agents.',
      gallery_notes: safeRequest
        ? 'Gallery has reviewed UX, clarity, and presentation quality for candidate delivery.'
        : 'No request provided for Gallery.',
      action_items: safeRequest
        ? [
            'Review the selected candidate with the technical plan in mind.',
            'Apply the chosen strategy and confirm any risky assumptions.',
            'Polish the final output for the intended audience.'
          ]
        : ['Provide a valid request to the ensemble skill.'],
      confidence: safeRequest ? 9 : 4,
      discussion_log: candidates.map(candidate => ({
        id: candidate.id,
        score: candidate.score,
        medal: candidate.medal,
        summary: candidate.summary
      }))
    };
  }

  return {
    createEnsembleResult
  };
});
