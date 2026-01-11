package com.android.pen15

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.android.pen15.core.ConnectionManager
import com.android.pen15.ui.crypto.CryptoToolsFragment
import com.android.pen15.ui.flipper.FlipperToolsFragment
import com.android.pen15.ui.network.NetworkToolsFragment
import kotlinx.coroutines.launch
import com.android.pen15.ui.utilities.UtilitiesFragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {
    
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var connectionBadge: View
    private lateinit var connectionStatusDot: View
    private lateinit var textConnectionStatus: TextView
    
    private val connectionManager = ConnectionManager.getInstance()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        setupViewPager()
        setupConnectionBadge()
        
        // Show connection modal on first launch
        if (savedInstanceState == null) {
            showConnectionModal()
        }
    }
    
    private fun initViews() {
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        connectionBadge = findViewById(R.id.connectionBadge)
        connectionStatusDot = findViewById(R.id.connectionStatusDot)
        textConnectionStatus = findViewById(R.id.textConnectionStatus)
        
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            // TODO: Open settings
        }
    }
    
    private fun setupViewPager() {
        val adapter = ToolsPagerAdapter(this)
        viewPager.adapter = adapter
        
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "🔧 Flipper"
                1 -> "🌐 Network"
                2 -> "🔐 Crypto"
                3 -> "⚙️ Utils"
                else -> ""
            }
        }.attach()
    }
    
    private fun setupConnectionBadge() {
        connectionBadge.setOnClickListener {
            showConnectionModal()
        }
        updateConnectionStatus(connectionManager.isConnected())
    }
    
    private fun showConnectionModal() {
        val dialog = BottomSheetDialog(this, R.style.Theme_Pen15)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_connection, null)
        
        val btnUSB = view.findViewById<Button>(R.id.btnConnectUSB)
        val btnBluetooth = view.findViewById<Button>(R.id.btnConnectBluetooth)
        val btnOffline = view.findViewById<Button>(R.id.btnContinueOffline)
        val statusIndicator = view.findViewById<View>(R.id.statusIndicator)
        val textStatus = view.findViewById<TextView>(R.id.textStatus)
        
        btnUSB.setOnClickListener {
            lifecycleScope.launch {
                connectionManager.connectUSB(this@MainActivity) { success ->
                    runOnUiThread {
                        updateConnectionStatus(success)
                        if (success) {
                            textStatus.text = "Connected via USB"
                            dialog.dismiss()
                        }
                    }
                }
            }
        }

        btnBluetooth.setOnClickListener {
            lifecycleScope.launch {
                connectionManager.connectBluetooth(this@MainActivity) { success ->
                    runOnUiThread {
                        updateConnectionStatus(success)
                        if (success) {
                            textStatus.text = "Connected via Bluetooth"
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        
        btnOffline.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.setContentView(view)
        dialog.show()
    }
    
    private fun updateConnectionStatus(connected: Boolean) {
        if (connected) {
            textConnectionStatus.text = "Connected"
            textConnectionStatus.setTextColor(getColor(R.color.status_connected))
            connectionStatusDot.setBackgroundResource(R.drawable.status_indicator)
        } else {
            textConnectionStatus.text = "Disconnected"
            textConnectionStatus.setTextColor(getColor(R.color.status_disconnected))
            connectionStatusDot.setBackgroundResource(R.drawable.status_indicator)
        }
    }
    
    private class ToolsPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 4
        
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> FlipperToolsFragment()
                1 -> NetworkToolsFragment()
                2 -> CryptoToolsFragment()
                3 -> UtilitiesFragment()
                else -> FlipperToolsFragment()
            }
        }
    }
}
