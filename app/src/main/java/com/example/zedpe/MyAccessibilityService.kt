package com.example.zedpe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

class MyAccessibilityService : AccessibilityService() {

    private val TAG = "MyAccessibilityService"

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Log.d(TAG, "Event: Type=${AccessibilityEvent.eventTypeToString(event.eventType)}, Pkg=${event.packageName}, Class=${event.className}")

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val sourceNode: AccessibilityNodeInfo? = event.source
            if (sourceNode == null) {
                // Log.d(TAG, "Source node is null for click event.")
                return
            }

            // Log.d(TAG, "Clicked node: ${sourceNode.className}, Text: '${sourceNode.text}', ContentDesc: '${sourceNode.contentDescription}', ViewId: ${sourceNode.viewIdResourceName}")

            val nodeProcessor = NodeProcessor(TAG, applicationContext)
            nodeProcessor.findAndProcessUpiLink(sourceNode)
        }
    }

    class NodeProcessor(private val tag: String, private val context: Context) {
        private val upiPattern = Pattern.compile("upi://pay[?][^\\s]+", Pattern.CASE_INSENSITIVE)

        fun findAndProcessUpiLink(startNode: AccessibilityNodeInfo?) {
            var currentNode = startNode
            var depth = 0
            val localVisitedNodes = mutableSetOf<AccessibilityNodeInfo>()

            try {
                while (currentNode != null && depth < 5) {
                    if (!localVisitedNodes.add(currentNode)) break

                    val nodeText = currentNode.text?.toString() ?: ""
                    val contentDesc = currentNode.contentDescription?.toString() ?: ""
                    val fullText = "$nodeText $contentDesc".trim()

                    // Log.d(tag, "Checking node (depth $depth): ${currentNode.className}, Clickable: ${currentNode.isClickable}, Text: '$nodeText', CD: '$contentDesc'")

                    if (fullText.isNotEmpty()) {
                        val matcher = upiPattern.matcher(fullText)
                        if (matcher.find()) {
                            val upiLink = matcher.group(0)
                            Log.i(tag, "UPI link detected: $upiLink")

                            var clickableNodeToProcess: AccessibilityNodeInfo? = null
                            if (currentNode.isClickable) {
                                clickableNodeToProcess = currentNode
                            } else {
                                val clickableParent = findClickableParent(currentNode, localVisitedNodes)
                                if (clickableParent != null) {
                                    // Log.d(tag, "Found clickable parent for link: ${clickableParent.viewIdResourceName ?: clickableParent.className}")
                                    clickableNodeToProcess = clickableParent
                                } else if (startNode?.isClickable == true && startNode == currentNode) {
                                    clickableNodeToProcess = startNode
                                }
                            }

                            if (clickableNodeToProcess != null) {
                                transformAndLaunchZedPe(upiLink)
                                return
                            }
                        }
                    }
                    val parent = currentNode.parent
                    currentNode = parent
                    depth++
                }
            } finally {
                localVisitedNodes.forEach { node ->
                    try {
                        if (node.refresh()) {
                            node.recycle()
                        }
                    } catch (e: IllegalStateException) {
                        // Log.w(tag, "Error recycling node: ${e.message}")
                    }
                }
            }
        }

        private fun findClickableParent(node: AccessibilityNodeInfo?, localVisitedNodes: MutableSet<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
            var parentNode = node?.parent
            var parentDepth = 0
            while (parentNode != null && parentDepth < 3) {
                if (!localVisitedNodes.add(parentNode)) return null
                if (parentNode.isClickable) {
                    return parentNode
                }
                parentNode = parentNode.parent
                parentDepth++
            }
            return null
        }

        private fun transformAndLaunchZedPe(upiLink: String) {
            try {
                val upiUri = Uri.parse(upiLink)
                if (!"upi".equals(upiUri.scheme, ignoreCase = true) || !"pay".equals(upiUri.authority, ignoreCase = true)) {
                    Log.w(tag, "Not a standard UPI payment link: $upiLink")
                    return
                }

                val payeeVpa = upiUri.getQueryParameter("pa")
                val amount = upiUri.getQueryParameter("am")
                val payeeName = upiUri.getQueryParameter("pn")
                val transactionRef = upiUri.getQueryParameter("tr")

                if (payeeVpa.isNullOrBlank()) {
                    Log.w(tag, "No 'pa' (Payee VPA) found in UPI link: $upiLink")
                    return
                }

                val zpiUriBuilder = Uri.Builder()
                    .scheme("zpi")
                    .authority("pay")
                    .appendQueryParameter("pa", payeeVpa)

                if (!amount.isNullOrBlank()) zpiUriBuilder.appendQueryParameter("am", amount)
                if (!payeeName.isNullOrBlank()) zpiUriBuilder.appendQueryParameter("pn", payeeName)
                if (!transactionRef.isNullOrBlank()) zpiUriBuilder.appendQueryParameter("tr", transactionRef)

                val zpiUri = zpiUriBuilder.build()
                Log.i(tag, "Transformed to ZPI link: $zpiUri")

                val intent = Intent(Intent.ACTION_VIEW, zpiUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    setPackage(context.packageName)
                }

                context.startActivity(intent)
                Log.i(tag, "Launched ZedPe with ZPI intent.")

            } catch (e: Exception) {
                Log.e(tag, "Error transforming or launching ZedPe: ${e.message}", e)
                if (e is ActivityNotFoundException) {
                    Log.e(tag, "ZedPe app not found or ZPI scheme not registered correctly in Manifest.")
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val currentServiceInfo = this.serviceInfo ?: AccessibilityServiceInfo()
        currentServiceInfo.flags = currentServiceInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        setServiceInfo(currentServiceInfo)
        Log.i(TAG, "Accessibility Service Connected and configured.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Accessibility Service Unbound")
        return super.onUnbind(intent)
    }
}