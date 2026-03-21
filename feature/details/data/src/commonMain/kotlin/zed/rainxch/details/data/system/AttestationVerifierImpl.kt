package zed.rainxch.details.data.system

import zed.rainxch.core.domain.logging.GitHubStoreLogger
import zed.rainxch.details.domain.repository.DetailsRepository
import zed.rainxch.details.domain.system.AttestationVerifier
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class AttestationVerifierImpl(
    private val detailsRepository: DetailsRepository,
    private val logger: GitHubStoreLogger,
) : AttestationVerifier {
    override suspend fun verify(
        owner: String,
        repoName: String,
        filePath: String,
    ): Boolean =
        try {
            val digest = computeSha256(filePath)
            detailsRepository.checkAttestations(owner, repoName, digest)
        } catch (e: Exception) {
            logger.debug("Attestation check error: ${e.message}")
            false
        }

    private fun computeSha256(filePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        FileInputStream(File(filePath)).use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
