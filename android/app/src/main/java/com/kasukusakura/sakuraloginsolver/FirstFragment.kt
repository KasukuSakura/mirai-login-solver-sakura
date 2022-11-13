package com.kasukusakura.sakuraloginsolver

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.JsonReader
import android.util.Log
import android.view.KeyEvent
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat.getSystemService
import androidx.navigation.fragment.findNavController
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kasukusakura.sakuraloginsolver.databinding.FragmentFirstBinding
import com.king.zxing.CameraScan
import com.king.zxing.CaptureActivity
import okhttp3.*
import java.io.IOException
import java.net.URI
import java.util.UUID

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class FirstFragment : Fragment() {
    private companion object {
        private const val LOG_NAME = "SakuraSolver"
        private const val LEGACY_ONLINE_SERVICE = "https://txhelper.glitch.me/"
    }

    private var _binding: FragmentFirstBinding? = null
    private var _client: OkHttpClient? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private val client: OkHttpClient get() = _client!!
    private lateinit var crtContext: ReqContext

    private lateinit var rawRequestLauncher: ActivityResultLauncher<Intent>
    private lateinit var qrScanLauncher: ActivityResultLauncher<Intent>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        _client = OkHttpClient()

        rawRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            requireActivity().runOnUiThread {
                crtContext.processAlert.dismiss()
            }
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { crtContext.complete?.invoke(it) }
            }
        }
        qrScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult

            val rsp0 = CameraScan.parseScanResult(result.data).orEmpty()
            Log.i(LOG_NAME, "QRScan Rsp: $rsp0")
            if (rsp0.isEmpty()) return@registerForActivityResult


            process(rsp0)
        }
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.reset.setOnClickListener {
            binding.urlOrId.text.clear()
        }
        binding.next.setOnClickListener {
            process(binding.urlOrId.text.toString())
        }
        binding.qrScan.setOnClickListener {
            qrScanLauncher.launch(Intent(requireActivity(), CaptureActivity::class.java))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        _client?.let { c ->
            c.dispatcher.cancelAll()
            c.connectionPool.evictAll()
            c.cache?.close()
        }
    }

    private class ReqContext(
        var processAlert: AlertDialog,
        val activity: Activity,
    ) {
        var complete: ((Intent) -> Unit)? = null
    }

    @Suppress("DEPRECATION")
    private fun submitBack(rspUrl: String, ticket: String) {
        submitBack(rspUrl, RequestBody.create(null, ticket))
    }

    private fun submitBack(rspUrl: String, rspbdy: RequestBody) {

        val activity = requireActivity()
        val alert = AlertDialog.Builder(activity)
            .setTitle("请稍后").setMessage("正在提交").setCancelable(false)
            .create()
        activity.runOnUiThread { alert.show() }

        client.newCall(
            Request.Builder().url(rspUrl).post(rspbdy).build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity.runOnUiThread {
                    alert.dismiss()
                    Toast.makeText(activity, e.message, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                activity.runOnUiThread {
                    alert.dismiss()
                    Toast.makeText(
                        activity,
                        "Done.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    private fun processSakuraRequest(context: ReqContext, toplevel: JsonObject) {
        val sport = toplevel["port"].asInt
        val reqId = toplevel["id"].asString
        val servers = toplevel["server"].asJsonArray

        fun processNext(iter: Iterator<JsonElement>) {
            if (!iter.hasNext()) {
                context.activity.runOnUiThread {
                    context.processAlert.cancel()
                    Toast.makeText(requireActivity(), "No any server available....", Toast.LENGTH_SHORT).show()
                }
                return
            }

            val serverIp = iter.next().asString
            val serverBase = "http://$serverIp:$sport"

            val urlx = "$serverBase/request/request/$reqId"
            Log.i(LOG_NAME, "Trying $urlx")

            client.newCall(
                Request.Builder()
                    .url(urlx)
                    .get()
                    .build()
            ).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(LOG_NAME, "Net error", e)
                    processNext(iter)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bdy = response.body
                    if (bdy == null || response.code != 200) {
                        processNext(iter)
                        return
                    }

                    val strx = bdy.string()
                    Log.i(LOG_NAME, strx)
                    processSakuraRequest2(context, serverIp, serverBase, strx)
                }
            })
        }

        processNext(servers.iterator())

    }

    private fun processSakuraRequest2(context: ReqContext, serverIp: String, serverBase: String, rawdata: String) {
        val toplevel = JsonParser.parseString(rawdata).asJsonObject

        val msgdata = toplevel["data"].asJsonObject
        crtContext = context
        val rspUrl = URI.create(serverBase).resolve(toplevel["rspuri"].asString).toString()

        val tunnel = toplevel["tunnel"]?.asString.orEmpty()
            .replace("<serverip>", serverIp)

        context.complete = { rspx ->
            val ticket = rspx.getStringExtra("ticket")!!

            submitBack(rspUrl, ticket)
        }

        when (msgdata["type"].asString) {
            "slider" -> {
                rawRequestLauncher.launch(
                    Intent(requireActivity(), CaptchaActivity::class.java)
                        .putExtra("url", msgdata["url"].asString)
                        .putExtra("tunnel", tunnel)
                )
            }
            else -> {
                requireActivity().runOnUiThread {
                    context.processAlert.cancel()
                    Toast.makeText(
                        requireActivity(),
                        "不支持的类型 " + msgdata["type"].asString + ", 请尝试更新",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }
        }
    }

    private fun processAsOnlineCode(context: ReqContext, reqcode: Int) {
        val remoteUrl = LEGACY_ONLINE_SERVICE + reqcode

        client.newCall(
            Request.Builder().url(remoteUrl).get().build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                requireActivity().runOnUiThread {
                    context.processAlert.cancel()
                    Toast.makeText(requireActivity(), e.message, Toast.LENGTH_SHORT).show()
                }

            }

            override fun onResponse(call: Call, response: Response) {
                requireActivity().runOnUiThread {
                    if (response.code == 200) {
                        val url = response.body!!.string()

                        crtContext = context
                        crtContext.complete = { rspx ->
                            val ticket = rspx.getStringExtra("ticket")!!
                            Log.i(LOG_NAME, "Response ticket: $ticket")
                            submitBack(
                                "$LEGACY_ONLINE_SERVICE/finish/$reqcode",
                                FormBody.Builder().add("ticket", ticket).build()
                            )
                        }

                        rawRequestLauncher.launch(
                            Intent(requireActivity(), CaptchaActivity::class.java)
                                .putExtra("url", url)
                        )
                    } else {
                        context.processAlert.cancel()
                        Toast.makeText(
                            requireActivity(),
                            "请求错误：" + response.code,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }


    private fun processAsRawRequest(context: ReqContext, url: String) {
        crtContext = context
        context.complete = fun(rsp) {
            if (rsp.hasExtra("srsp")) {
                requireActivity().runOnUiThread {
                    context.processAlert.show()
                }
                val srsp = rsp.getStringExtra("srsp")!!
                Log.i(LOG_NAME, srsp)
                val urix = URI.create(url)
                processSakuraRequest2(context, urix.host, "${urix.scheme}://${urix.host}:${urix.port}", srsp)
                return
            }

            val ticket = rsp.getStringExtra("ticket")!!
            Log.i(LOG_NAME, "Response ticket: $ticket")

            requireActivity().runOnUiThread {
                AlertDialog.Builder(requireActivity()).setTitle("Ticket").setMessage(ticket).setPositiveButton(
                    "复制"
                ) { _, _ ->  // dialog, which
                    val clipboardManager =
                        requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val result = ClipData.newPlainText(null, ticket)
                    clipboardManager.setPrimaryClip(result)
                    Toast.makeText(requireActivity(), "复制成功", Toast.LENGTH_SHORT).show()
                }.show()
            }

        }
        rawRequestLauncher.launch(
            Intent(requireActivity(), CaptchaActivity::class.java)
                .putExtra("url", url)
                .putExtra("raw-direct", true)
        )
    }

    private fun process(data: String) {
        val context = ReqContext(
            processAlert = AlertDialog.Builder(requireActivity())
                .setTitle("Processing...")
                .setMessage("Please wait...")
                .setCancelable(false)
                .create(),
            activity = requireActivity()
        )
        context.activity.runOnUiThread {
            context.processAlert.show()
        }

        kotlin.runCatching {
            JsonParser.parseString(data).asJsonObject
        }.onSuccess {
            processSakuraRequest(context, it)
            return
        }

        val reqcode = try {
            data.toInt()
        } catch (_: java.lang.Exception) {
            -1
        }
        if (reqcode > 0) {
            processAsOnlineCode(context, reqcode)
        } else {
            processAsRawRequest(context, data)
        }
    }
}