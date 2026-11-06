"""Tests for the cold-candidate DPP kernel.

Run from the repository root:

    python3 -m pytest spike -q
"""

from __future__ import annotations

import numpy as np
import pytest

from spike.kernel.kernel import (
    DEFAULT_RHO,
    build_kernel,
    embed,
    fast_greedy_dpp,
    fit_whitening,
    modality_matrix,
    normalise_quality,
)

RNG = np.random.default_rng(7)


def random_unit(n: int, d: int = 32, seed: int = 0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return _unit(rng.normal(size=(n, d)))


def _unit(x: np.ndarray) -> np.ndarray:
    return x / np.linalg.norm(x, axis=-1, keepdims=True)


def min_eig(matrix: np.ndarray) -> float:
    return float(np.linalg.eigvalsh(matrix).min())


# --------------------------------------------------------------------------
# PSD
# --------------------------------------------------------------------------


def test_modality_matrix_is_psd_for_every_valid_rho() -> None:
    mask = np.array([True] * 5 + [False] * 4)
    for rho in (0.0, 0.3, 0.7, 1.0):
        m = modality_matrix(mask, rho)
        assert min_eig(m) >= -1e-9


def test_audio_only_kernel_is_psd() -> None:
    u = random_unit(120)
    kernel = build_kernel(u, np.ones(120, dtype=bool), np.ones(120))
    assert kernel.shape == (120, 120)
    assert min_eig(kernel) > 0.0


def test_text_only_kernel_is_psd() -> None:
    u = random_unit(120, seed=1)
    kernel = build_kernel(u, np.zeros(120, dtype=bool), np.ones(120))
    assert min_eig(kernel) > 0.0


def test_mixed_kernel_is_psd() -> None:
    u = random_unit(200, seed=2)
    is_audio = np.zeros(200, dtype=bool)
    is_audio[:100] = True
    q = normalise_quality(RNG.random(200))
    kernel = build_kernel(u, is_audio, q)
    assert min_eig(kernel) > 0.0


def test_whitening_preserves_psd() -> None:
    x = RNG.normal(size=(300, 16)) @ RNG.normal(size=(16, 16))
    mean, w = fit_whitening(x)
    u = embed(x, mean, w)
    is_audio = np.zeros(300, dtype=bool)
    is_audio[::2] = True
    kernel = build_kernel(u, is_audio, np.ones(300))
    assert min_eig(kernel) > 0.0


def test_cauchy_schwarz_holds_for_every_pair() -> None:
    u = random_unit(60, seed=3)
    is_audio = RNG.random(60) > 0.5
    kernel = build_kernel(u, is_audio, np.ones(60))
    diag = np.diag(kernel)
    bound = np.sqrt(np.outer(diag, diag))
    assert np.all(np.abs(kernel) <= bound + 1e-9)


def test_cholesky_never_fails_across_seeds() -> None:
    for seed in range(200):
        rng = np.random.default_rng(seed)
        n = int(rng.integers(2, 80))
        u = _unit(rng.normal(size=(n, 24)))
        is_audio = rng.random(n) > 0.5
        q = normalise_quality(rng.random(n))
        kernel = build_kernel(u, is_audio, q)
        np.linalg.cholesky(kernel)  # raises if not positive definite


# --------------------------------------------------------------------------
# The three cases from the spike plan
# --------------------------------------------------------------------------


def test_cross_modal_pairs_are_scaled_and_same_modal_pairs_are_not() -> None:
    u = random_unit(4, d=8, seed=4)
    is_audio = np.array([True, True, False, False])
    rho = 0.6
    kernel = build_kernel(u, is_audio, np.ones(4), rho=rho)
    gram = u @ u.T

    assert kernel[0, 1] == pytest.approx(gram[0, 1], abs=1e-9)  # (a) audio-audio
    assert kernel[2, 3] == pytest.approx(gram[2, 3], abs=1e-9)  # (c) text-text
    assert kernel[0, 2] == pytest.approx(rho * gram[0, 2], abs=1e-9)  # (b) cross
    assert kernel[1, 3] == pytest.approx(rho * gram[1, 3], abs=1e-9)


def test_kernel_diagonal_is_quality_squared() -> None:
    u = random_unit(10, seed=5)
    is_audio = np.zeros(10, dtype=bool)
    q = np.linspace(0.1, 1.0, 10)
    kernel = build_kernel(u, is_audio, q)
    lam = 1e-6 * np.mean(q**2)
    assert np.allclose(np.diag(kernel), q**2 + lam, atol=1e-9)


def test_quality_carries_no_cold_penalty() -> None:
    """A text item and an audio item with equal relevance have equal quality."""
    u = random_unit(2, seed=6)
    kernel = build_kernel(u, np.array([True, False]), np.array([0.5, 0.5]))
    diag = np.diag(kernel)
    assert diag[0] == pytest.approx(diag[1], abs=1e-12)


def test_rho_must_be_a_correlation() -> None:
    with pytest.raises(ValueError):
        modality_matrix(np.array([True, False]), rho=1.5)


def test_beta_must_be_non_negative() -> None:
    u = random_unit(3, seed=7)
    with pytest.raises(ValueError):
        build_kernel(u, np.ones(3, dtype=bool), np.ones(3), beta=-0.1)


# --------------------------------------------------------------------------
# Selection behaviour
# --------------------------------------------------------------------------


def test_returns_requested_number_of_unique_indices() -> None:
    u = random_unit(80, seed=8)
    is_audio = RNG.random(80) > 0.5
    picked = fast_greedy_dpp(build_kernel(u, is_audio, np.ones(80)), k=30)
    assert len(picked) == 30
    assert len(set(picked)) == 30
    assert all(0 <= i < 80 for i in picked)


def test_duplicate_candidate_is_not_selected_twice() -> None:
    u = random_unit(40, seed=9)
    u[1] = u[0]
    picked = fast_greedy_dpp(build_kernel(u, np.ones(40, dtype=bool), np.ones(40)), k=5)
    assert not ({0, 1} <= set(picked))


def test_greedy_matches_exhaustive_greedy() -> None:
    u = random_unit(9, d=6, seed=10)
    is_audio = np.array([True, False] * 4 + [True])
    kernel = build_kernel(u, is_audio, np.ones(9))
    assert fast_greedy_dpp(kernel, k=4) == _naive_greedy(kernel, k=4)


def _naive_greedy(kernel: np.ndarray, k: int) -> list[int]:
    remaining = set(range(kernel.shape[0]))
    chosen: list[int] = []
    for _ in range(k):
        best, best_value = -1, -np.inf
        for candidate in remaining:
            subset = chosen + [candidate]
            value = float(np.linalg.det(kernel[np.ix_(subset, subset)]))
            if value > best_value:
                best, best_value = candidate, value
        chosen.append(best)
        remaining.remove(best)
    return chosen


def test_all_cold_slate_covers_distinct_clusters() -> None:
    """With equal quality, a top-k would fill from one cluster; the DPP must not."""
    rng = np.random.default_rng(11)
    centres = _unit(rng.normal(size=(6, 32)))
    labels = np.repeat(np.arange(6), 10)
    u = _unit(centres[labels] + 0.05 * rng.normal(size=(60, 32)))
    picked = fast_greedy_dpp(
        build_kernel(u, np.zeros(60, dtype=bool), np.ones(60)), k=12
    )
    assert len(picked) == 12
    assert len({int(labels[i]) for i in picked}) == 6


def test_slate_similarity_beats_random_subsets() -> None:
    u = random_unit(60, seed=11)
    picked = fast_greedy_dpp(
        build_kernel(u, np.zeros(60, dtype=bool), np.ones(60)), k=20
    )
    gram = u @ u.T
    np.fill_diagonal(gram, 0.0)

    def mean_sq(indices: np.ndarray) -> float:
        block = gram[np.ix_(indices, indices)]
        np.fill_diagonal(block, 0.0)
        return float((block**2).mean())

    rng = np.random.default_rng(12)
    random_subsets = [rng.choice(60, size=20, replace=False) for _ in range(200)]
    random_scores = np.array([mean_sq(s) for s in random_subsets])
    assert mean_sq(np.array(picked)) < np.quantile(random_scores, 0.05)


# --------------------------------------------------------------------------
# Quality construction
# --------------------------------------------------------------------------


def test_quality_never_underflows() -> None:
    # In float32, exp(-120) is already zero. Embeddings are float32 on device,
    # so exp(-squared distance) returns exactly 0 for every candidate and the
    # DPP degenerates to argmax of a zero vector.
    distances = np.array([120.0, 140.0, 160.0, 180.0], dtype=np.float32)
    assert np.all(np.exp(-distances) == 0.0)

    q = normalise_quality(-distances.astype(np.float64))
    assert np.all(q > 0.0)
    assert q.max() <= 1.0


def test_quality_handles_a_constant_slate() -> None:
    q = normalise_quality(np.full(5, 0.42))
    assert np.allclose(q, 1.0)
