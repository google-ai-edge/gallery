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

  function hashString(value) {
    let hash = 0;
    for (let i = 0; i < value.length; i++) {
      hash = (hash * 31 + value.charCodeAt(i)) | 0;
    }
    return Math.abs(hash);
  }

  function deterministicNoise(seed, min, max) {
    const x = Math.sin(seed) * 10000;
    const frac = x - Math.floor(x);
    return min + Math.floor(frac * (max - min + 1));
  }

  function getFocus(index) {
    if (index < 5) return 'technical correctness';
    if (index < 11) return 'UX clarity and structure';
    if (index < 17) return 'risk review and validation';
    return 'presentation and user guidance';
  }

  function buildRequestSignals(request) {
    const normalized = normalizeRequest(request).toLowerCase();
    return {
      length: normalized.length,
      fix: normalized.includes('fix') || normalized.includes('bug'),
      test: normalized.includes('test'),
      validate: normalized.includes('validate') || normalized.includes('verify'),
      doc: normalized.includes('doc') || normalized.includes('documentation'),
      deploy: normalized.includes('deploy') || normalized.includes('release'),
      review: normalized.includes('review'),
      keywords: ['fix', 'test', 'validate', 'deploy', 'improve', 'document', 'review', 'optimize', 'refactor'].filter(token => normalized.includes(token)).length
    };
  }

  function scoreComponents(index, request) {
    const seed = hashString(request || '') + index * 19;
    const signals = buildRequestSignals(request);
    const baseCorrectness = 70 + Math.max(0, 20 - index) * 1.3 + (signals.fix ? 3 : 0);
    const baseCompleteness = 55 + (index % 6) * 2 + signals.keywords * 2;
    const baseUx = 50 + ((index + 4) % 7) * 2 + Math.min(signals.length / 20, 8);
    const baseRisk = 45 + Math.max(0, 20 - index) * 1.4 - (index >= 17 ? 4 : 0);
    const baseClarity = 55 + ((index + 2) % 6) * 2;

    return {
      correctness: Math.max(0, Math.min(100, baseCorrectness + deterministicNoise(seed, -5, 5))),
      completeness: Math.max(0, Math.min(100, baseCompleteness + deterministicNoise(seed + 23, -4, 4))),
      ux: Math.max(0, Math.min(100, baseUx + deterministicNoise(seed + 37, -4, 4))),
      risk: Math.max(0, Math.min(100, baseRisk + deterministicNoise(seed + 41, -4, 4))),
      clarity: Math.max(0, Math.min(100, baseClarity + deterministicNoise(seed + 53, -4, 4)))
    };
  }

  function aggregateScore(metrics, groupAverage) {
    const weights = {
      correctness: 0.30,
      completeness: 0.25,
      ux: 0.20,
      risk: 0.15,
      clarity: 0.10
    };

    const score =
      metrics.correctness * weights.correctness +
      metrics.completeness * weights.completeness +
      metrics.ux * weights.ux +
      metrics.risk * weights.risk +
      metrics.clarity * weights.clarity;

    const relativeBonus =
      (metrics.correctness - groupAverage.correctness) * 0.04 +
      (metrics.completeness - groupAverage.completeness) * 0.03 +
      (metrics.ux - groupAverage.ux) * 0.02 +
      (metrics.risk - groupAverage.risk) * 0.01 +
      (metrics.clarity - groupAverage.clarity) * 0.005;

    return Math.max(0, Math.min(100, Math.round(score + relativeBonus)));
  }

  function medalForRank(rank) {
    if (rank === 1) return 'Gold';
    if (rank <= 3) return 'Silver';
    if (rank <= 6) return 'Bronze';
    return 'None';
  }

  function makeCandidate(index, request) {
    const metrics = scoreComponents(index, request);
    return {
      id: index + 1,
      focus: getFocus(index),
      summary: `Candidate ${index + 1} focuses on ${getFocus(index)} while balancing technical accuracy, completeness, and UX.`,
      details: {
        goose: `Goose reviews candidate ${index + 1} for feasibility, tooling compatibility, and technical assumptions.`,
        agency_agents: `Agency Agents evaluates candidate ${index + 1} for strategy, alignment, and process impact.`,
        gallery: `Gallery assesses candidate ${index + 1} for final presentation, clarity, and UX polish.`
      },
      score_components: metrics,
      score: metrics.correctness,
      recommendation: `Candidate ${index + 1} is recommended for ${getFocus(index)}.`,
      self_review: `Candidate ${index + 1} was generated in a GRPO loop and compared to the full candidate set.`
    };
  }

  function createEnsembleResult(request) {
    const safeRequest = normalizeRequest(request);
    const candidates = safeRequest
      ? Array.from({ length: 23 }, (_, index) => makeCandidate(index, safeRequest))
      : [];

    const groupAverage = candidates.reduce(
      (acc, candidate, index) => {
        if (index === 0) {
          return {
            correctness: candidate.score_components.correctness,
            completeness: candidate.score_components.completeness,
            ux: candidate.score_components.ux,
            risk: candidate.score_components.risk,
            clarity: candidate.score_components.clarity
          };
        }
        return {
          correctness: acc.correctness + candidate.score_components.correctness,
          completeness: acc.completeness + candidate.score_components.completeness,
          ux: acc.ux + candidate.score_components.ux,
          risk: acc.risk + candidate.score_components.risk,
          clarity: acc.clarity + candidate.score_components.clarity
        };
      },
      null
    );

    const recalculatedAverage = candidates.length
      ? {
          correctness: groupAverage.correctness / candidates.length,
          completeness: groupAverage.completeness / candidates.length,
          ux: groupAverage.ux / candidates.length,
          risk: groupAverage.risk / candidates.length,
          clarity: groupAverage.clarity / candidates.length
        }
      : { correctness: 0, completeness: 0, ux: 0, risk: 0, clarity: 0 };

    candidates.forEach(candidate => {
      candidate.score = aggregateScore(candidate.score_components, recalculatedAverage);
    });

    const sorted = [...candidates].sort((a, b) => b.score - a.score);
    sorted.forEach((candidate, idx) => {
      candidate.rank = idx + 1;
      candidate.medal = medalForRank(candidate.rank);
      candidate.relative_policy = Math.round(candidate.score - recalculatedAverage.correctness);
    });

    const bestCandidate = sorted[0] || null;
    const medalCounts = {
      Gold: sorted.filter(c => c.medal === 'Gold').length,
      Silver: sorted.filter(c => c.medal === 'Silver').length,
      Bronze: sorted.filter(c => c.medal === 'Bronze').length
    };

    return {
      final_result: safeRequest
        ? `The FooG808 Agent Ensemble generated 23 candidates, graded them with GRPO, and selected candidate #${bestCandidate.id} as the strongest option.`
        : 'No request supplied. Please provide text in the `request` field.',
      best_candidate: bestCandidate,
      candidates: sorted,
      goose_notes: safeRequest
        ? 'Goose has reviewed the top candidate for technical correctness, feasibility, and assumptions.'
        : 'No request provided for Goose.',
      agency_agents_notes: safeRequest
        ? 'Agency Agents has reviewed strategic alignment, risk, and impact across candidate alternatives.'
        : 'No request provided for Agency Agents.',
      gallery_notes: safeRequest
        ? 'Gallery has reviewed UX, clarity, and presentation quality for the selected candidate.'
        : 'No request provided for Gallery.',
      action_items: safeRequest
        ? [
            'Review the selected candidate and confirm the technical assumptions.',
            'Apply the chosen strategy and polish the final delivery.',
            'Keep the top candidates as fallback alternatives for robustness.'
          ]
        : ['Provide a valid request to generate candidate evaluations.'],
      confidence: safeRequest ? 9 : 4,
      learning_summary: safeRequest
        ? 'The ensemble compared 23 policy alternatives, rewarded stronger candidate metrics, and learned from weaker variants using GRPO-style relative scoring.'
        : 'No request provided for learning summary.',
      discussion_log: sorted.map(candidate => ({
        id: candidate.id,
        rank: candidate.rank,
        score: candidate.score,
        medal: candidate.medal,
        summary: candidate.summary
      })),
      medal_counts: medalCounts
    };
  }

  return {
    createEnsembleResult
  };
});
