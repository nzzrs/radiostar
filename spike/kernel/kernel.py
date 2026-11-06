"""PSD-safe DPP kernel over a mixed audio / text candidate set.

The candidate pool mixes two embeddings that live in the same CLAP space:

* a track that has been played has an *audio* embedding ``a_i``;
* a candidate that has never been played has only a *text* embedding ``t_i``.

Both are unit vectors in R^512 after centring and whitening, so a single Gram
matrix ``G = U U^T`` describes every pair. The only thing that differs between
the two sources is trust: a text vector is a weaker description of the track
than an audio vector, so a *cross-modal* pair should count for less than a
same-modal pair. That is a per-pair discount, not a per-item discount.

Kernel
------
    M_ij = 1     if i and j share a modality (both audio or both text)
         = rho   otherwise, 0 <= rho <= 1

    S    = M (*) G                     (*) elementwise product
    L_ij = q_i q_j S_ij
    L   += lambda I,   lambda = jitter_rel * mean(diag(L))

Unrolled for the three cases in the spike plan:

    (a) audio vs audio : S_ij = G_ij
    (b) audio vs text  : S_ij = rho * G_ij
    (c) text  vs text  : S_ij = G_ij

PSD proof
---------
1. ``G = U U^T`` is a Gram matrix, therefore PSD.
2. ``M`` is a two-block matrix with ones on the diagonal and ``rho`` on the
   off-diagonal blocks. Its eigenvalues are ``1 - rho`` and ``1 + rho``, so it
   is PSD for ``|rho| <= 1``.
3. Schur product theorem: the elementwise product of two PSD matrices is PSD.
4. ``diag(q) S diag(q)`` is a congruence, which preserves PSD.
5. ``lambda I`` with ``lambda > 0`` makes the result strictly positive
   definite, so Cholesky cannot see a zero or negative pivot for numerical
   reasons alone.

For a Gram matrix Cauchy-Schwarz holds, ``S_ij^2 <= S_ii S_jj``, which is
exactly the condition that every Schur complement in the incremental Cholesky
stays non-negative.

Why not ``c_i c_j``
-------------------
An earlier draft used ``S_ij = c_i c_j G_ij + beta`` with ``c = rho`` for text
items. That form also scales the *diagonal* to ``rho^2``, so every cold
candidate carries a self-similarity of 0.49 against 1.0 for a played track,
and the DPP systematically prefers played tracks. The block form keeps the
diagonal at 1 and damps only the cross-modal pairs, which is the behaviour the
cold-start design actually needs.

``beta`` is kept as an optional non-negative offset for callers that require
non-negative similarities. It is 0 by default: a constant added to every entry
flattens contrast and does not improve conditioning (``lambda`` does that).
"""

from __future__ import annotations

import numpy as np
from numpy.typing import NDArray

EPS = 1e-12
DEFAULT_RHO = 0.7


def l2_normalise(x: NDArray[np.float64], axis: int = -1) -> NDArray[np.float64]:
    """Return ``x`` scaled to unit norm along ``axis``, safe for zero rows."""
    norm = np.linalg.norm(x, axis=axis, keepdims=True)
    return x / np.maximum(norm, EPS)


def fit_whitening(
    x: NDArray[np.float64], eps: float = 1e-5
) -> tuple[NDArray[np.float64], NDArray[np.float64]]:
    """Fit ZCA whitening on an embedding store.

    Returns ``(mean, W)`` such that ``(x - mean) @ W.T`` has identity
    covariance. Whitening removes the anisotropy that makes almost every pair
    of CLAP vectors look 0.9-similar, which is what starves the DPP of
    diversity signal. A linear map preserves PSD, so the kernel proof stands.
    """
    mean = x.mean(axis=0)
    centred = x - mean
    denom = max(len(x) - 1, 1)
    cov = (centred.T @ centred) / denom
    values, vectors = np.linalg.eigh(cov)
    values = np.maximum(values, eps)
    w = (vectors * (1.0 / np.sqrt(values))) @ vectors.T
    return mean, w


def embed(x: NDArray[np.float64], mean: NDArray[np.float64], w: NDArray[np.float64]) -> NDArray[np.float64]:
    """Whiten then L2-normalise. Diagonal scaling after a linear map keeps PSD."""
    return l2_normalise((x - mean) @ w.T)


def modality_matrix(is_audio: NDArray[np.bool_], rho: float = DEFAULT_RHO) -> NDArray[np.float64]:
    """Two-block modality correlation matrix ``M``, PSD for ``|rho| <= 1``."""
    if not -1.0 <= rho <= 1.0:
        raise ValueError(f"rho must lie in [-1, 1], got {rho}")
    mask = np.asarray(is_audio, dtype=bool)
    same = mask[:, None] == mask[None, :]
    return np.where(same, 1.0, float(rho))


def normalise_quality(scores: NDArray[np.float64], eps: float = 1e-9) -> NDArray[np.float64]:
    """Map raw relevance scores into ``[eps, 1]`` per slate.

    Never use ``exp(-distance)`` here: in 512 dimensions that underflows to
    exactly zero for every candidate and the DPP returns index 0.
    """
    s = np.asarray(scores, dtype=np.float64)
    low, high = float(s.min()), float(s.max())
    if high - low < eps:
        return np.ones_like(s)
    return np.maximum((s - low) / (high - low), eps)


def build_kernel(
    u: NDArray[np.float64],
    is_audio: NDArray[np.bool_],
    q: NDArray[np.float64],
    rho: float = DEFAULT_RHO,
    beta: float = 0.0,
    jitter_rel: float = 1e-6,
) -> NDArray[np.float64]:
    """Build the DPP kernel ``L`` in float64.

    ``u`` is ``(n, d)`` with unit rows, ``is_audio`` is ``(n,)`` and ``q`` is
    ``(n,)`` with non-negative quality values.
    """
    u = np.asarray(u, dtype=np.float64)
    q = np.asarray(q, dtype=np.float64)
    if u.ndim != 2:
        raise ValueError("u must be a 2-D array")
    if len(q) != len(u) or len(is_audio) != len(u):
        raise ValueError("u, is_audio and q must agree on n")
    if np.any(q < 0):
        raise ValueError("quality must be non-negative")
    if beta < 0:
        raise ValueError("beta must be non-negative")

    gram = u @ u.T
    s = modality_matrix(is_audio, rho) * gram + float(beta)
    kernel = np.outer(q, q) * s

    lam = float(jitter_rel) * float(np.mean(np.diag(kernel)))
    if not np.isfinite(lam) or lam <= 0.0:
        lam = 1e-9
    kernel = kernel + lam * np.eye(len(q), dtype=np.float64)
    return kernel


def fast_greedy_dpp(
    kernel: NDArray[np.float64], k: int, eps: float = 1e-12
) -> list[int]:
    """Fast greedy MAP for a DPP, incremental Cholesky (Chen et al., 2018).

    ``O(n k^2)``. Every pivot is clamped before ``sqrt``; a candidate whose
    marginal gain collapses is dropped rather than turned into a NaN.
    """
    kernel = np.asarray(kernel, dtype=np.float64)
    n = kernel.shape[0]
    if n == 0 or k <= 0:
        return []
    k = min(int(k), n)

    di2 = np.diag(kernel).copy()
    cis = np.zeros((k, n), dtype=np.float64)
    selected: list[int] = []

    j = int(np.argmax(di2))
    for i in range(k):
        if di2[j] <= eps:
            break
        selected.append(j)

        row = kernel[j, :] - cis[:i, j] @ cis[:i, :]
        cis[i, :] = row / np.sqrt(di2[j])

        di2 = di2 - cis[i, :] ** 2
        di2[selected] = -np.inf
        j = int(np.argmax(di2))

    return selected


def select_slate(
    u: NDArray[np.float64],
    is_audio: NDArray[np.bool_],
    scores: NDArray[np.float64],
    k: int,
    rho: float = DEFAULT_RHO,
    beta: float = 0.0,
    jitter_rel: float = 1e-6,
) -> list[int]:
    """Normalise quality, build the kernel and return ``k`` selected indices."""
    q = normalise_quality(scores)
    kernel = build_kernel(u, is_audio, q, rho=rho, beta=beta, jitter_rel=jitter_rel)
    return fast_greedy_dpp(kernel, k)
