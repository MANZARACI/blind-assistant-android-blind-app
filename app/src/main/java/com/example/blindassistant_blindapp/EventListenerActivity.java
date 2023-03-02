package com.example.blindassistant_blindapp;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.Locale;

public class EventListenerActivity extends AppCompatActivity {
    private FirebaseAuth mAuth;
    private FirebaseUser currentUser;
    private DatabaseReference mDatabase, mDetectedFacesRef, mLocationRequestRef;
    private ValueEventListener detectedFacesListener, locationRequestListener;
    private TextToSpeech textToSpeech;
    private RequestQueue queue;
    private static final String resetDetectedFacesUrl = BuildConfig.BACKEND_URL + "/reset-detected-faces";
    private static final String newLocationUrl = BuildConfig.BACKEND_URL + "/location";
    private FusedLocationProviderClient fusedLocationClient;
    ActivityResultLauncher<String[]> locationPermissionRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_event_listener);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        queue = Volley.newRequestQueue(this);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        textToSpeech = new TextToSpeech(getApplicationContext(), i -> {
            if (i != TextToSpeech.ERROR) {
                textToSpeech.setLanguage(Locale.ENGLISH);
            }
        });

        locationPermissionRequest = registerForActivityResult(new ActivityResultContracts
                .RequestMultiplePermissions(), result -> {
            Boolean fineLocationGranted = result.getOrDefault(
                    Manifest.permission.ACCESS_FINE_LOCATION, false
            );
            Boolean coarseLocationGranted = result.getOrDefault(
                    Manifest.permission.ACCESS_COARSE_LOCATION, false
            );

            if (fineLocationGranted != null && fineLocationGranted) {
                // Precise location access granted.
                sendLocation();
            } else if (coarseLocationGranted != null && coarseLocationGranted) {
                // Only approximate location access granted.
                sendLocation();
            } else {
                Toast.makeText(EventListenerActivity.this, R.string.denied_permission_text, Toast.LENGTH_LONG).show();
                textToSpeech.speak(getString(R.string.denied_permission_text), TextToSpeech.QUEUE_FLUSH, null, null);
            }
        });

        detectedFacesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot faceLabelSnapshot : snapshot.getChildren()) {
                    textToSpeech.speak(faceLabelSnapshot.getValue(String.class), TextToSpeech.QUEUE_ADD, null, null);
                }

                sendResetDetectedFacesRequest();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                textToSpeech.speak(getString(R.string.getting_detected_faces_error), TextToSpeech.QUEUE_FLUSH, null, null);

                sendResetDetectedFacesRequest();
            }
        };

        locationRequestListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {

                if (Boolean.TRUE.equals(snapshot.getValue(Boolean.class))) {
                    sendLocation();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {

            }
        };
    }

    private void sendLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionRequest.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        } else {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(this, location -> {
                        if (location != null) {
                            currentUser.getIdToken(true).addOnSuccessListener(getTokenResult -> {
                                JSONObject jsonBody = new JSONObject();
                                try {
                                    jsonBody.put("idToken", getTokenResult.getToken());
                                    jsonBody.put("lat", location.getLatitude());
                                    jsonBody.put("lng", location.getLongitude());
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                                final String requestBody = jsonBody.toString();

                                StringRequest stringRequest = new StringRequest(Request.Method.POST, newLocationUrl, response -> {
                                    Toast.makeText(EventListenerActivity.this, response, Toast.LENGTH_LONG)
                                            .show();
                                    textToSpeech.speak(response, TextToSpeech.QUEUE_FLUSH, null, null);
                                }, error -> {
                                    Toast.makeText(EventListenerActivity.this, R.string.sending_location_error, Toast.LENGTH_LONG)
                                            .show();
                                    textToSpeech.speak(getString(R.string.sending_location_error), TextToSpeech.QUEUE_FLUSH, null, null);
                                }) {
                                    @Override
                                    public String getBodyContentType() {
                                        return "application/json; charset=utf-8";
                                    }

                                    @Override
                                    public byte[] getBody() throws AuthFailureError {
                                        try {
                                            return requestBody == null ? null : requestBody.getBytes("utf-8");
                                        } catch (UnsupportedEncodingException uee) {
                                            VolleyLog.wtf("Unsupported Encoding while trying to get the bytes of %s using %s",
                                                    requestBody, "utf-8");
                                            return null;
                                        }
                                    }
                                };

                                queue.add(stringRequest);
                            });
                        }
                    });
        }
    }

    private void sendResetDetectedFacesRequest() {
        currentUser.getIdToken(true).addOnSuccessListener(getTokenResult -> {
            JSONObject jsonBody = new JSONObject();
            try {
                jsonBody.put("idToken", getTokenResult.getToken());
            } catch (JSONException e) {
                e.printStackTrace();
            }
            final String requestBody = jsonBody.toString();

            StringRequest stringRequest = new StringRequest(Request.Method.POST, resetDetectedFacesUrl, response -> {

            }, error -> {
                textToSpeech.speak(getString(R.string.resetting_detected_faces_error), TextToSpeech.QUEUE_FLUSH, null, null);
            }) {
                @Override
                public String getBodyContentType() {
                    return "application/json; charset=utf-8";
                }

                @Override
                public byte[] getBody() throws AuthFailureError {
                    try {
                        return requestBody == null ? null : requestBody.getBytes("utf-8");
                    } catch (UnsupportedEncodingException uee) {
                        VolleyLog.wtf("Unsupported Encoding while trying to get the bytes of %s using %s",
                                requestBody, "utf-8");
                        return null;
                    }
                }
            };

            queue.add(stringRequest);
        });

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.actionSignout) {
            if (currentUser != null && mAuth != null) {
                textToSpeech.speak(getString(R.string.signing_out_tts), TextToSpeech.QUEUE_FLUSH, null, null);
                mDetectedFacesRef.removeEventListener(detectedFacesListener);
                mLocationRequestRef.removeEventListener(locationRequestListener);
                mAuth.signOut();
                startActivity(new Intent(EventListenerActivity.this, MainActivity.class));
                finish();
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();
        currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            if (mDetectedFacesRef == null) {
                mDetectedFacesRef = mDatabase.child("users").child(currentUser.getUid()).child("detectedFaces");
                mDetectedFacesRef.addValueEventListener(detectedFacesListener);
            }

            if (mLocationRequestRef == null) {
                mLocationRequestRef = mDatabase.child("users").child(currentUser.getUid()).child("locationRequest");
                mLocationRequestRef.addValueEventListener(locationRequestListener);
            }

        }
    }
}