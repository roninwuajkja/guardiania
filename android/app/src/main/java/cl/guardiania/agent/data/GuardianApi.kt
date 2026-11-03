package cl.guardiania.agent.data

import retrofit2.Response
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface GuardianApi {
    @POST("api/v1/pairing/verify")
    suspend fun verify(@Body body: VerifyRequest): VerifyResponse

    @POST("api/v1/devices/refresh")
    suspend fun refresh(@Body body: RefreshRequest): RefreshResponse

    @POST("api/v1/captures/sync")
    suspend fun sync(
        @Header("Authorization") authorization: String,
        @Body body: SyncRequest,
    ): SyncResponse

    @POST("api/v1/captures/evaluate")
    suspend fun evaluate(
        @Header("Authorization") authorization: String,
        @Body body: CaptureVector,
    ): EvaluationResponse

    @POST("api/v1/devices/self/unlink")
    suspend fun unlinkSelf(
        @Header("Authorization") authorization: String,
        @Body body: UnlinkRequest,
    ): Response<Unit>

    @POST("api/v1/devices/self/status")
    suspend fun reportStatus(
        @Header("Authorization") authorization: String,
        @Body body: DeviceStatusRequest,
    ): Response<Unit>

    @POST("api/v1/auth/login")
    suspend fun tutorLogin(@Body body: TutorLoginRequest): TutorAuthDto

    @POST("api/v1/auth/register")
    suspend fun tutorRegister(@Body body: TutorRegisterRequest): TutorRegisterDto

    @POST("api/v1/auth/refresh")
    suspend fun tutorRefresh(@Body body: TutorRefreshRequest): TutorAuthDto

    @POST("api/v1/auth/logout")
    suspend fun tutorLogout(
        @Header("Authorization") authorization: String,
        @Body body: TutorRefreshRequest,
    ): Response<Unit>

    @GET("api/v1/auth/me")
    suspend fun tutorMe(@Header("Authorization") authorization: String): TutorProfileDto

    @GET("api/v1/overview")
    suspend fun tutorOverview(@Header("Authorization") authorization: String): TutorOverviewDto

    @GET("api/v1/alerts")
    suspend fun tutorAlerts(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 200,
    ): List<TutorAlertDto>

    @PATCH("api/v1/alerts/{alertId}")
    suspend fun tutorUpdateAlert(
        @Header("Authorization") authorization: String,
        @Path("alertId") alertId: String,
        @Body body: AlertStateRequest,
    ): TutorAlertDto

    @GET("api/v1/devices")
    suspend fun tutorDevices(@Header("Authorization") authorization: String): List<TutorDeviceDto>

    @POST("api/v1/devices/{bindingId}/revoke")
    suspend fun tutorRevoke(
        @Header("Authorization") authorization: String,
        @Path("bindingId") bindingId: String,
    ): Response<Unit>

    @PUT("api/v1/devices/{bindingId}/unlink-code")
    suspend fun tutorSetUnlinkCode(
        @Header("Authorization") authorization: String,
        @Path("bindingId") bindingId: String,
        @Body body: TutorUnlinkCodeRequest,
    ): Response<Unit>

    @DELETE("api/v1/devices/{bindingId}/unlink-code")
    suspend fun tutorClearUnlinkCode(
        @Header("Authorization") authorization: String,
        @Path("bindingId") bindingId: String,
    ): Response<Unit>

    @POST("api/v1/pairing/generate")
    suspend fun tutorGeneratePairing(
        @Header("Authorization") authorization: String,
        @Body body: TutorPairingRequest,
    ): TutorPairingDto

    @GET("api/v1/pairing/{sessionId}")
    suspend fun tutorPairingStatus(
        @Header("Authorization") authorization: String,
        @Path("sessionId") sessionId: String,
    ): TutorPairingStatusDto

    @POST("api/v1/pairing/{sessionId}/cancel")
    suspend fun tutorCancelPairing(
        @Header("Authorization") authorization: String,
        @Path("sessionId") sessionId: String,
    ): Response<Unit>

    @POST("api/v1/auth/password/change")
    suspend fun tutorChangePassword(
        @Header("Authorization") authorization: String,
        @Body body: TutorChangePasswordRequest,
    ): Response<Unit>

    @POST("api/v1/auth/logout-all")
    suspend fun tutorLogoutAll(
        @Header("Authorization") authorization: String,
    ): Response<Unit>

    @POST("api/v1/auth/verify-email/resend")
    suspend fun tutorResendVerification(@Body body: TutorEmailRequest): TutorSimpleMessage

    @GET("api/v1/account/consents")
    suspend fun tutorConsents(@Header("Authorization") authorization: String): List<TutorConsentDto>

    @Streaming
    @GET("api/v1/account/export")
    suspend fun tutorExport(
        @Header("Authorization") authorization: String,
    ): Response<ResponseBody>

    @POST("api/v1/realtime/ticket")
    suspend fun tutorRealtimeTicket(
        @Header("Authorization") authorization: String,
    ): RealtimeTicketDto

    @POST("api/v1/push/tokens")
    suspend fun tutorRegisterPushToken(
        @Header("Authorization") authorization: String,
        @Body body: PushTokenRequestDto,
    ): PushTokenDto

    @POST("api/v1/push/test")
    suspend fun tutorTestPush(
        @Header("Authorization") authorization: String,
        @Query("delay_seconds") delaySeconds: Int = 0,
    ): PushTestDto

    @POST("api/v1/push/tokens/unregister")
    suspend fun tutorUnregisterPushToken(
        @Header("Authorization") authorization: String,
        @Body body: PushTokenRequestDto,
    ): Response<Unit>
}
