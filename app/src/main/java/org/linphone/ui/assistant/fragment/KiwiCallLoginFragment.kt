/*
 * Copyright (c) 2026 KiwiCall.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.assistant.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.AssistantKiwicallLoginFragmentBinding
import org.linphone.ui.GenericActivity
import org.linphone.ui.GenericFragment
import org.linphone.ui.assistant.viewmodel.KiwiCallLoginViewModel

@UiThread
class KiwiCallLoginFragment : GenericFragment() {
    companion object {
        private const val TAG = "[KiwiCall Login Fragment]"
    }

    private lateinit var binding: AssistantKiwicallLoginFragmentBinding

    private val viewModel: KiwiCallLoginViewModel by navGraphViewModels(R.id.assistant_nav_graph)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = AssistantKiwicallLoginFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel
        observeToastEvents(viewModel)

        binding.setBackClickListener {
            findNavController().popBackStack()
        }

        binding.setScanQrCodeClickListener {
            findNavController().navigate(
                KiwiCallLoginFragmentDirections.actionKiwiCallLoginFragmentToQrCodeScannerFragment()
            )
        }

        binding.setManualSipClickListener {
            findNavController().navigate(
                KiwiCallLoginFragmentDirections.actionKiwiCallLoginFragmentToThirdPartySipAccountLoginFragment()
            )
        }

        viewModel.showPassword.observe(viewLifecycleOwner) {
            lifecycleScope.launch {
                delay(50)
                binding.password.setSelection(binding.password.text?.length ?: 0)
            }
        }

        viewModel.accountLoggedInEvent.observe(viewLifecycleOwner) {
            it.consume {
                Log.i("$TAG Account successfully configured, leaving assistant")
                requireActivity().finish()
            }
        }

        viewModel.loginErrorEvent.observe(viewLifecycleOwner) {
            it.consume { message ->
                (requireActivity() as GenericActivity).showRedToast(message, R.drawable.warning_circle)
            }
        }
    }
}
