package com.macroindustry.O

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Signaling exchange over Firestore, keyed by a 6-digit room code.
 *
 * Firestore layout:
 *   rooms/{code}
 *     .offer     : { sdp, type }        (written by Host)
 *     .answer    : { sdp, type }        (written by Viewer)
 *   rooms/{code}/hostCandidates/{auto}  : ICE candidates from Host
 *   rooms/{code}/viewerCandidates/{auto}: ICE candidates from Viewer
 *
 * Firestore security rules must allow open read/write on `rooms/{code}`
 * documents for this room-code-only pairing to work (no auth). Scope
 * this by TTL/cleanup in the console since anyone with a code can join —
 * acceptable for a 6-digit code shared directly between your two devices.
 */
class RoomSignaling(private val roomCode: String) {

    companion object {
        private const val TAG = "RoomSignaling"
    }

    private val db = FirebaseFirestore.getInstance()
    private val roomRef = db.collection("rooms").document(roomCode)
    private val listeners = mutableListOf<ListenerRegistration>()

    fun sendOffer(sdp: SessionDescription) {
        val data = mapOf("sdp" to sdp.description, "type" to sdp.type.canonicalForm())
        roomRef.set(mapOf("offer" to data), com.google.firebase.firestore.SetOptions.merge())
    }

    fun sendAnswer(sdp: SessionDescription) {
        val data = mapOf("sdp" to sdp.description, "type" to sdp.type.canonicalForm())
        roomRef.set(mapOf("answer" to data), com.google.firebase.firestore.SetOptions.merge())
    }

    fun sendIceCandidate(candidate: IceCandidate, isHost: Boolean) {
        val subcollection = if (isHost) "hostCandidates" else "viewerCandidates"
        val data = mapOf(
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "candidate" to candidate.sdp
        )
        roomRef.collection(subcollection).add(data)
            .addOnFailureListener { Log.w(TAG, "Failed to send ICE candidate: ${it.message}") }
    }

    /** Viewer calls this to be notified when the Host publishes an offer. */
    fun listenForOffer(onOffer: (SessionDescription) -> Unit) {
        val reg = roomRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "listenForOffer error: ${error.message}")
                return@addSnapshotListener
            }
            val offerMap = snapshot?.get("offer") as? Map<*, *> ?: return@addSnapshotListener
            val sdp = offerMap["sdp"] as? String ?: return@addSnapshotListener
            val type = offerMap["type"] as? String ?: return@addSnapshotListener
            onOffer(SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
        }
        listeners.add(reg)
    }

    /** Host calls this to be notified when the Viewer publishes an answer. */
    fun listenForAnswer(onAnswer: (SessionDescription) -> Unit) {
        val reg = roomRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "listenForAnswer error: ${error.message}")
                return@addSnapshotListener
            }
            val answerMap = snapshot?.get("answer") as? Map<*, *> ?: return@addSnapshotListener
            val sdp = answerMap["sdp"] as? String ?: return@addSnapshotListener
            val type = answerMap["type"] as? String ?: return@addSnapshotListener
            onAnswer(SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
        }
        listeners.add(reg)
    }

    /** Listen for remote ICE candidates. isHost=true means "I am the host", so listen to viewerCandidates. */
    fun listenForIceCandidates(isHost: Boolean, onCandidate: (IceCandidate) -> Unit) {
        val subcollection = if (isHost) "viewerCandidates" else "hostCandidates"
        val reg = roomRef.collection(subcollection).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "listenForIceCandidates error: ${error.message}")
                return@addSnapshotListener
            }
            snapshot?.documentChanges?.forEach { change ->
                if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                    val doc = change.document
                    val sdpMid = doc.getString("sdpMid") ?: return@forEach
                    val sdpMLineIndex = (doc.getLong("sdpMLineIndex") ?: 0).toInt()
                    val candidate = doc.getString("candidate") ?: return@forEach
                    onCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
                }
            }
        }
        listeners.add(reg)
    }

    /**
     * Clears any stale offer/answer/candidates left over from a previous
     * session before starting a fresh handshake on the same reused
     * pairing code. Without this, a Viewer reconnecting later could pick
     * up a dead offer from hours ago.
     */
    fun resetForNewSession(onComplete: () -> Unit) {
        val hostCandidates = roomRef.collection("hostCandidates").get()
        val viewerCandidates = roomRef.collection("viewerCandidates").get()

        hostCandidates.addOnSuccessListener { docs ->
            docs.forEach { it.reference.delete() }
            viewerCandidates.addOnSuccessListener { vDocs ->
                vDocs.forEach { it.reference.delete() }
                roomRef.set(mapOf("offer" to null, "answer" to null))
                    .addOnCompleteListener { onComplete() }
            }
        }
    }

    /** Call when leaving/tearing down to stop listening (does not delete room data). */
    fun stopListening() {
        listeners.forEach { it.remove() }
        listeners.clear()
    }

    /** Wipes the room document + candidate subcollections. Call on clean disconnect. */
    fun cleanupRoom() {
        roomRef.collection("hostCandidates").get().addOnSuccessListener { docs ->
            docs.forEach { it.reference.delete() }
        }
        roomRef.collection("viewerCandidates").get().addOnSuccessListener { docs ->
            docs.forEach { it.reference.delete() }
        }
        roomRef.delete()
    }
}
