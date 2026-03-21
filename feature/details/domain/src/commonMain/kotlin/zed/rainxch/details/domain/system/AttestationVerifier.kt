package zed.rainxch.details.domain.system

/**
 * Verifies build attestations for downloaded assets using GitHub's
 * supply-chain security API.
 */
interface AttestationVerifier {
    /**
     * Computes the SHA-256 digest of [filePath] and checks whether
     * the repository [owner]/[repoName] has a matching attestation.
     *
     * @return `true` if a valid attestation exists, `false` otherwise.
     */
    suspend fun verify(
        owner: String,
        repoName: String,
        filePath: String,
    ): Boolean
}
