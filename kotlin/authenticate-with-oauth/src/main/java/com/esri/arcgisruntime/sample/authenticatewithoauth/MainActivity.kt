/*
 * Copyright 2019 Esri
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.esri.arcgisruntime.sample.authenticatewithoauth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Browser
import android.support.annotation.RequiresApi
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.portal.Portal
import com.esri.arcgisruntime.portal.PortalItem
import com.esri.arcgisruntime.security.AuthenticationChallenge
import com.esri.arcgisruntime.security.AuthenticationChallengeHandler
import com.esri.arcgisruntime.security.AuthenticationChallengeResponse
import com.esri.arcgisruntime.security.AuthenticationManager
import com.esri.arcgisruntime.security.OAuthConfiguration
import com.esri.arcgisruntime.security.OAuthTokenCredentialRequest
import com.esri.arcgisruntime.security.UserCredential
import kotlinx.android.synthetic.main.activity_main.*

/**
 * This sample demonstrates how to authenticate with ArcGIS Online (or your own portal) using OAuth2 to access secured
 * resources (such as private web maps or layers). Accessing secured items requires a login on the portal that hosts them
 * (an ArcGIS Online account, for example). This sample utilizes Android WebView to show theOAuth sign-in page in a dialog.
 *
 * The user's access token should be treated with the upmost of security, and as such, we do not recommend storing the user's
 * Access Token in SharedPreferences as SharedPreferences are not encrypted. We have created this sample to highlight the use
 * of the ArcGISRuntime API.
 *
 * Please use the Android keystore provider where available: https://developer.android.com/training/articles/keystore#UsingAndroidKeyStore
 */
class MainActivity : AppCompatActivity(), AuthenticationChallengeHandler {

  companion object {
    private val TAG = MainActivity::class.java.simpleName

    // maximum expiry is 2 weeks = 20160
    private const val ACCESS_TOKEN_EXPIRY_MINS = 20160L
  }

  // define configuration for OAuth Portal using custom redirect URL to receive code after auth has been granted
  private val oAuthConfig: OAuthConfiguration by lazy {
    OAuthConfiguration(
      getString(R.string.portal_url),
      getString(R.string.oauth_client_id),
      getString(R.string.oauth_redirect_uri)
    )
  }

  // instances of Portal and PortalItem to define what is displayed in the map
  private val portal: Portal by lazy { Portal(getString(R.string.portal_url)) }

  private val portalItem: PortalItem by lazy { PortalItem(portal, getString(R.string.webmap_world_traffic_id)) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // setup AuthenticationManager to handle auth challenges
    AuthenticationManager.setAuthenticationChallengeHandler(this)
    AuthenticationManager.addOAuthConfiguration(oAuthConfig)
  }

  override fun onResume() {
    super.onResume()
    mapView.resume()

    handleIntent(intent)

    mapView.map = ArcGISMap(portalItem)
  }

  override fun onPause() {
    mapView.pause()
    super.onPause()
  }

  override fun onDestroy() {
    mapView.dispose()
    super.onDestroy()
  }

  /**
   * Attempt to handle the Intent received by the Activity
   * If the Intent contains an authorization code, store this in the SharedPreferences
   *
   * @param intent the Intent to handle
   */
  private fun handleIntent(intent: Intent?) {
    intent?.authCode?.let { code ->
      sharedPreferences.putAuthCode(code)
    }
  }

  /**
   * Function for handling authentication challenges.
   *
   * Tries to use an access token if one is stored in SharedPreferences, if the token has expired, clear the token and
   * expiry time and begin OAuth flow.
   *
   * If an authorization code exists in the SharedPreferences, it's likely that the user is partially through the OAuth flow
   * and we need to try to obtain the token by performing a OAuthTokenCredentialRequest.
   *
   * If there is neither an access token or an authorization code in the SharedPreferences, it's likely that this is the
   * user's first attempt at OAuth. So begin OAuth flow.
   *
   * @param authenticationChallenge the authentication challenge to handle
   * @return the AuthenticationChallengeResponse indicating which action to take
   */
  override fun handleChallenge(authenticationChallenge: AuthenticationChallenge?): AuthenticationChallengeResponse {
    authenticationChallenge?.let { authChallenge ->

      try {
        // if SharedPreferences has an access token
        sharedPreferences.accessToken?.let { accessToken ->
          // USER_CREDENTIAL_CHALLENGE is issued when attempt to use access token fails
          if (authChallenge.type == AuthenticationChallenge.Type.USER_CREDENTIAL_CHALLENGE) {
            // check for expiration of token
            sharedPreferences.accessTokenExpiry.let {
              // if expiry hasn't been set, the value of the expiry will be 0. If we have an expiry, we check if the value
              // is less than the current epoch reported by the device
              if (it in 1 until System.currentTimeMillis()) {
                // access token has expired so clear the token and expiry
                sharedPreferences.clearAccessToken()
                // begin OAuth flow to generate auth code
                beginOAuth()
                // treat as cancel
                return AuthenticationChallengeResponse(AuthenticationChallengeResponse.Action.CANCEL, null)
              }
            }
          }

          // OAUTH_CREDENTIAL_CHALLENGE is issued when the portal is detected as supporting OAuth
          if (authChallenge.type == AuthenticationChallenge.Type.OAUTH_CREDENTIAL_CHALLENGE) {
            // attempt to use stored access token for auth
            return AuthenticationChallengeResponse(
              AuthenticationChallengeResponse.Action.CONTINUE_WITH_CREDENTIAL,
              UserCredential.createFromToken(accessToken, authChallenge.remoteResource?.uri)
            )
          }
        }

        // if SharedPreferences has an auth code, we've likely just been through the OAuth flow and now have an auth code
        // we can use to request a new access token
        sharedPreferences.authCode?.let {
          // use the authorization code to get a new access token by executing an OAuthTokenCredentialRequest
          val request = OAuthTokenCredentialRequest(
            oAuthConfig.portalUrl,
            null,
            oAuthConfig.clientId,
            oAuthConfig.redirectUri,
            sharedPreferences.authCode
          )

          val credential = request.executeAsync().get()
          with(sharedPreferences) {
            putAccessToken(credential.accessToken)
            putAccessTokenExpiry(System.currentTimeMillis() + (ACCESS_TOKEN_EXPIRY_MINS * 60))
            clearAuthCode()
          }
          // continue with credentials generated using auth code
          return AuthenticationChallengeResponse(
            AuthenticationChallengeResponse.Action.CONTINUE_WITH_CREDENTIAL,
            credential
          )
        }

        // we only want to begin OAuth here if the user has yet to authorize successfully
        if (authChallenge.failureCount < 2) {
          beginOAuth()
        }

        return AuthenticationChallengeResponse(AuthenticationChallengeResponse.Action.CANCEL, null)
      } catch (e: Exception) {
        // auth code has likely expired, clear existing auth code and begin OAuth flow
        getString(R.string.error_auth_exception, e.message).let {
          Log.d(TAG, it)
          runOnUiThread {
            logToUser(it)
          }
        }
        sharedPreferences.clearAuthCode()
        beginOAuth()
      }
    }
    return AuthenticationChallengeResponse(AuthenticationChallengeResponse.Action.CANCEL, null)
  }

  private fun beginOAuth() {
    // get the authorization code by sending user to the authorization screen
    val authorizationUrl = OAuthTokenCredentialRequest.getAuthorizationUrl(
      oAuthConfig.portalUrl, oAuthConfig.clientId, oAuthConfig.redirectUri, ACCESS_TOKEN_EXPIRY_MINS
    )

    // create an Intent to attempt to handle the authorization URL
    with(Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl))) {
      this.resolveActivity(packageManager)?.let {
        // this identifier ensures that the browser will attempt to reuse the same window each time the application
        // launches the browser with the same identifier, which in this case is static and will always only be our
        // application ID
        this.putExtra(Browser.EXTRA_APPLICATION_ID, BuildConfig.APPLICATION_ID)
        startActivity(this)
        return
      }
    }

    // user doesn't have a browser available to handle the Intent so we use a WebView to handle OAuth. WebView methods
    // must be called on UI thread
    runOnUiThread {
      setupWebView()
      webView.loadUrl(authorizationUrl)
    }
  }

  private fun setupWebView() {
    // setup a WebViewClient to override handling of custom scheme and host for Intent
    webView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        Uri.parse(url)?.let {
          if (it.scheme == "my-ags-app" && it.host == "auth") {
            startActivity(generateAuthIntent(it))
            return true
          }
        }
        return super.shouldOverrideUrlLoading(view, url)
      }

      @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
      override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request?.url?.scheme == "my-ags-app" && request.url?.host == "auth") {
          startActivity(generateAuthIntent(request.url))
          return true
        }
        return super.shouldOverrideUrlLoading(view, request)
      }
    }

    // enabled to allow javascript to run on auth webpage.
    webView.settings.javaScriptEnabled = true
    webView.visibility = View.VISIBLE
  }

  /**
   * Generate the Intent that launches the MainActivity with the new auth code
   *
   * @param uri instance of Uri generated from URL that OAuth webpage redirects to after successful login
   */
  private fun generateAuthIntent(uri: Uri): Intent {
    return Intent().also {
      it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
      it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      it.data = uri
    }
  }

}

/**
 * AppCompatActivity extensions
 */
val AppCompatActivity.sharedPreferences: SharedPreferences
  get() = this.getSharedPreferences("${this::class.java.simpleName}_shared_prefs", Context.MODE_PRIVATE)

fun AppCompatActivity.logToUser(logMessage: String) {
  Log.d(this::class.java.simpleName, logMessage)
  Toast.makeText(this, logMessage, Toast.LENGTH_LONG).show()
}

/**
 * SharedPreferences extensions
 *
 * The user's access token should be treated with the upmost of security, and as such, we do not recommend storing the user's
 * Access Token in SharedPreferences as SharedPreferences are not encrypted. We have created this sample to highlight the use
 * of the ArcGISRuntime API.
 *
 * Please use the Android keystore provider where available: https://developer.android.com/training/articles/keystore#UsingAndroidKeyStore
 */
fun SharedPreferences.putAuthCode(authCode: String) {
  this.edit().putString("auth_code", authCode)
    .apply()
}

val SharedPreferences.authCode: String?
  get() = this.getString("auth_code", null)

fun SharedPreferences.clearAuthCode() {
  this.edit().remove("auth_code")
    .apply()
}

val SharedPreferences.accessToken: String?
  get() = this.getString("access_token", null)

fun SharedPreferences.putAccessToken(accessToken: String) {
  this.edit().putString("access_token", accessToken)
    .apply()
}

fun SharedPreferences.clearAccessToken() {
  this.edit().remove("access_token")
    .apply()
  this.clearAccessTokenExpiry()
}

val SharedPreferences.accessTokenExpiry: Long
  get() = this.getLong("access_token_expiry", 0)

fun SharedPreferences.putAccessTokenExpiry(timeInMillis: Long) {
  this.edit().putLong("access_token_expiry", timeInMillis)
    .apply()
}

fun SharedPreferences.clearAccessTokenExpiry() {
  this.edit().remove("access_token_expiry")
    .apply()
}

/**
 * Intent extensions
 */
val Intent.authCode: String?
  get() = this.data?.getQueryParameter("code")