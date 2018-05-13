package com.edwardlu.controllerapp;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.TimeUtils;

import net.named_data.jndn.Data;
import net.named_data.jndn.Face;
import net.named_data.jndn.Interest;
import net.named_data.jndn.InterestFilter;
import net.named_data.jndn.Name;
import net.named_data.jndn.NetworkNack;
import net.named_data.jndn.OnData;
import net.named_data.jndn.OnInterestCallback;
import net.named_data.jndn.OnNetworkNack;
import net.named_data.jndn.OnRegisterFailed;
import net.named_data.jndn.OnRegisterSuccess;
import net.named_data.jndn.OnTimeout;
import net.named_data.jndn.Sha256WithEcdsaSignature;
import net.named_data.jndn.encoding.EncodingException;
import net.named_data.jndn.encoding.der.DerDecodingException;
import net.named_data.jndn.encoding.tlv.TlvDecoder;
import net.named_data.jndn.encoding.tlv.TlvEncoder;
import net.named_data.jndn.security.EcKeyParams;
import net.named_data.jndn.security.KeyChain;
import net.named_data.jndn.security.OnInterestValidationFailed;
import net.named_data.jndn.security.OnVerified;
import net.named_data.jndn.security.SafeBag;
import net.named_data.jndn.security.SecurityException;
import net.named_data.jndn.security.SigningInfo;
import net.named_data.jndn.security.ValidatorConfigError;
import net.named_data.jndn.security.ValidityPeriod;
import net.named_data.jndn.security.VerificationHelpers;
import net.named_data.jndn.security.certificate.IdentityCertificate;
import net.named_data.jndn.security.identity.IdentityManager;
import net.named_data.jndn.security.identity.MemoryIdentityStorage;
import net.named_data.jndn.security.identity.MemoryPrivateKeyStorage;
import net.named_data.jndn.security.pib.Pib;
import net.named_data.jndn.security.pib.PibIdentity;
import net.named_data.jndn.security.pib.PibImpl;
import net.named_data.jndn.security.policy.PolicyManager;
import net.named_data.jndn.security.tpm.Tpm;
import net.named_data.jndn.security.tpm.TpmBackEnd;
import net.named_data.jndn.security.v2.CertificateV2;
import net.named_data.jndn.security.v2.InterestValidationFailureCallback;
import net.named_data.jndn.security.v2.InterestValidationSuccessCallback;
import net.named_data.jndn.security.v2.ValidationError;
import net.named_data.jndn.security.v2.ValidationPolicyFromPib;
import net.named_data.jndn.security.v2.Validator;
import net.named_data.jndn.util.Blob;
import net.named_data.jndn.util.Common;
import net.named_data.jndn.util.SignedBlob;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.security.Identity;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;


public class NFDService extends Service {

    private static String TAG = "NFDService";

    // strings for the intent filter
    public final static String NFD_STOPPED = "NFD_STOPPED";
    public final static String FACE_CLOSED = "FACE_CLOSED";
    public final static String INTEREST_RECEIVED = "INTEREST_RECEIVED";
    public final static String INTEREST_DISCOVER_RECEIVED = "INTEREST_DISCOVER_RECEIVED";
    public final static String INTEREST_CONNECT_GET_SERVICES_RECEIVED = "INTEREST_CONNECT_RECEIVED";
    public final static String DATA_RECEIVED = "DATA_RECEIVED";
    public final static String INTEREST_SENT = "INTEREST_SENT";
    public final static String DATA_SENT = "DATA_SENT";
    public final static String INTEREST_TIMEOUT = "INTEREST_TIMEOUT";
    public final static String INTEREST_NACK = "INTEREST_NACK";
    public final static String INTEREST_DISCONNECT_RECEIVED = "INTEREST_DISCONNECT_RECEIVED";
    public final static String INTEREST_CHARACTERISTIC_ACTION_RECEIVED = "INTEREST_CHARACTERISTIC_ACTION_RECEIVED";

    // strings for intent extras
    public final static String INTEREST_NAME = "INTEREST_NAME";
    public final static String INTEREST_MAC = "INTEREST_CONNECT_MAC";
    public final static String CHARACTERISTIC_ACTION_DATA = "CHARACTERISTIC_ACTION_DATA";
    public final static String CHARACTERISTIC_WRITE_DATA = "CHARACTERISTIC_WRITE_DATA";

    private Face face;
    public KeyChain keyChain;
    boolean alreadyRunning;
    CertificateV2 controllerCertificate;
    PibIdentity createdIdentity;

    // maps BKpubHash to device info
    public HashMap<String, DeviceInfo> devices;

    public static class DeviceInfo {

        DeviceInfo(CertificateV2 BKpubInput, long tokenInput) {
            BKpub = BKpubInput;
            token = tokenInput;
        }

        CertificateV2 BKpub;
        long token;
    };

    public void startNetworkThread() {
        if (!alreadyRunning) {
            networkThread.start();
        }
    }

    // sends string data to NFD face, using the name passed in for the data name
    public void sendDataToNFDFace(String data, String prefix) {

        Log.d(TAG, "we entered the sendDataToNFDFace function with the extra prefix information");

        Name dataName = new Name(prefix);

        Log.d(TAG, dataName.toString());

        Data sendData = new Data();
        sendData.setName(dataName);
        Blob content = new Blob(data.toString());
        sendData.setContent(content);

        Log.d(TAG, content.toString());

        class OneShotTask implements Runnable {
            Data data;
            OneShotTask(Data d) { data = d; }
            public void run() {
                try {
                    face.putData(data);
                } catch (IOException e) {
                    Log.d(TAG, "failure when responding to data interest: " + e.toString());
                }
            }
        }
        Thread t = new Thread(new OneShotTask(sendData));
        t.start();

    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "we got told to destroy ourselves");
        super.onDestroy();
        face.shutdown();
        networkThread.interrupt();
    }

    private void registerDataPrefix () {
        Log.d(TAG, "registering prefixes...");
        try {
            face.registerPrefix(new Name(getString(R.string.bootstrapPrefix)), OnBootstrappingRequest,
                    OnPrefixRegisterFailed, OnPrefixRegisterSuccess);
            face.registerPrefix(new Name(getString(R.string.homePrefix)).append("cert"), OnCertificateRequest,
                    OnPrefixRegisterFailed, OnPrefixRegisterSuccess);

        } catch (IOException | SecurityException e) {
            // should also be handled in callback, but in just in case...
        }
    }

    public void testKeysFunction() {


        Interest request = new Interest(
                new Name("/ndn/sign-on/6C05A21A2940029D372883C39BFFF0046BE38D7571319356A310D03F04C2E20B/fakeSignature"));

        // /ndn/sign-on/Hash(BKpub)/{ECDSA signature by BKpri}
        Name name = request.getName();
        String BKpubHash = name.get(2).toEscapedString();
        Log.d(TAG, "BKpubHash of interest: " + BKpubHash);
        String BKpriSignatureString = name.get(3).toEscapedString();
        Log.d(TAG, "BKpriSignature: " + BKpriSignatureString);

        // TODO-2: zhiyi, please verify the signature here
        if (VerificationHelpers.verifyInterestSignature(request, MainActivity.lastDeviceCertificate)) {
            Log.d(TAG, "verifying bootstrapping BKpri interest signature was successful");
        }
        else {
            Log.d(TAG, "verification of bootstrapping BKpri interest signature failed");
        }

        if (!devices.containsKey(BKpubHash)) {
            Log.d(TAG, "haven't scanned the QR code for this device yet, ignoring bootstrapping request");
            return;
        }
        else {
            Log.d(TAG, "the device's BKpubHash matched a certificate from a qr code we scanned earlier, " +
                    "proceeding to process boostrapping request");
        }

        CertificateV2 BKpub = devices.get(BKpubHash).BKpub;
        // TODO-3: zhiyi, please verify the hash of BKpub here

        CertificateV2 anchorCert = controllerCertificate;
        SecureRandom randomNumberGenerator = new SecureRandom();
        long token = Math.abs(randomNumberGenerator.nextLong());
        devices.get(BKpubHash).token = token;


        // TODO-4: zhiyi, please encrypt controller's public key, token1, token2 by BKpub, and then add the encryption to the data content

        SignedBlob anchorCertBytes = anchorCert.wireEncode();

        Blob testBlob = new Blob(MainActivity.lastDeviceCertificate.getContent());
        byte[] doubleBKpubBytesBuffer = new byte[testBlob.getImmutableArray().length * 2];
        byte[] BKpubByteArray = testBlob.getImmutableArray();

        Log.d(TAG, "BKpubByteARray length: " + BKpubByteArray.length);
        Log.d(TAG, "doubleBKpubBytesBuffer lenght: " + doubleBKpubBytesBuffer.length);
        Log.d(TAG, "BKpubByteArray: " + Arrays.toString(BKpubByteArray));

        System.arraycopy(BKpubByteArray, 0, doubleBKpubBytesBuffer, 0, BKpubByteArray.length);
        System.arraycopy(BKpubByteArray, 0, doubleBKpubBytesBuffer, BKpubByteArray.length, BKpubByteArray.length);

        Log.d(TAG, "doubleBKpubBytesBuffer: " + Arrays.toString(doubleBKpubBytesBuffer));

        ByteBuffer DoubleBKpubHashByteBuffer = ByteBuffer.wrap(doubleBKpubBytesBuffer);

        Log.d(TAG, "doubleBKpubBytesBuffer after wrapping: " + Arrays.toString(DoubleBKpubHashByteBuffer.array()));

        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            // Don't expect this to happen.
            throw new Error
                    ("MessageDigest: SHA-256 is not supported: " + exception.getMessage());
        }

        sha256.update(doubleBKpubBytesBuffer, 0, doubleBKpubBytesBuffer.length);
        byte[] doubleBKpubDigestBytes = sha256.digest();
        Blob digestBytesBlob = new Blob(doubleBKpubDigestBytes);
        String doubleBKpubHashDigestBytesString = digestBytesBlob.toHex();
        Log.d(TAG, doubleBKpubHashDigestBytesString);

        TlvEncoder tlvEncodedDataContent = new TlvEncoder();

        // Encode backwards.
        tlvEncodedDataContent.writeBlobTlv(130, ByteBuffer.wrap(doubleBKpubDigestBytes));
        Log.d(TAG, "Length of doubleBKPubDigestBytes: " + doubleBKpubDigestBytes.length);
        Log.d(TAG, "Length of tlvEncodedDataContent after writing doubleBKpubHashDigestBytes: " + tlvEncodedDataContent.getLength());

        tlvEncodedDataContent.writeNonNegativeIntegerTlv(129, devices.get(BKpubHash).token);
        Log.d(TAG, "value of token from BKpubHash: " + Long.toString(devices.get(BKpubHash).token));
        Log.d(TAG, "Length of tlvEncodedDataContent after writing token: " + tlvEncodedDataContent.getLength());

        tlvEncodedDataContent.writeBuffer(anchorCert.wireEncode().buf());
        //tlvEncodedDataContent.writeBlobTlv(6, anchorCertBytes.buf());
        Log.d(TAG, "Length of anchorCertBytes: " + anchorCertBytes.signedSize());
        Log.d(TAG, "Length of tlvEncodedDataContent after writing anchorCertBytes: " + tlvEncodedDataContent.getLength());

        tlvEncodedDataContent.writeTypeAndLength(21, tlvEncodedDataContent.getLength());
        Log.d(TAG, "Length of tlvEncodedDataContent after writing type and length: " + tlvEncodedDataContent.getLength());

        //TlvDecoder tlvDecoder = new TlvDecoder()

        //Log.d(TAG, "tlv encoded data content output to string: " + tlvEncodedDataContent.getOutput().toString());

        byte[] finalDataContentByteArray = tlvEncodedDataContent.getOutput().array();

        Blob content = new Blob(finalDataContentByteArray);

    }

    private final OnInterestCallback OnBootstrappingRequest = new OnInterestCallback() {
        @Override
        public void onInterest(Name prefix, Interest request, final Face face, long interestFilterId, InterestFilter filter) {
            Log.d(TAG, "Got bootstrapping request: " + request.getName().toString() + "\n");

            // /ndn/sign-on/Hash(BKpub)/{ECDSA signature by BKpri}
            Name name = request.getName();
            String BKpubHash = name.get(2).toEscapedString();
            Log.d(TAG, "BKpubHash of interest: " + BKpubHash);
            String BKpriSignatureString = name.get(3).toEscapedString();
            Log.d(TAG, "BKpriSignature: " + BKpriSignatureString);

            // TODO-2: zhiyi, please verify the signature here
            if (VerificationHelpers.verifyInterestSignature(request, MainActivity.lastDeviceCertificate)) {
                Log.d(TAG, "verifying bootstrapping BKpri interest signature was successful");
            }
            else {
                Log.d(TAG, "verification of bootstrapping BKpri interest signature failed");
            }

            if (!devices.containsKey(BKpubHash)) {
                Log.d(TAG, "haven't scanned the QR code for this device yet, ignoring bootstrapping request");
                return;
            }
            else {
                Log.d(TAG, "the device's BKpubHash matched a certificate from a qr code we scanned earlier, " +
                        "proceeding to process boostrapping request");
            }

            CertificateV2 BKpub = devices.get(BKpubHash).BKpub;
            // TODO-3: zhiyi, please verify the hash of BKpub here

            CertificateV2 anchorCert = controllerCertificate;
            SecureRandom randomNumberGenerator = new SecureRandom();
            long token = Math.abs(randomNumberGenerator.nextLong());
            devices.get(BKpubHash).token = token;


            // TODO-4: zhiyi, please encrypt controller's public key, token1, token2 by BKpub, and then add the encryption to the data content

            SignedBlob anchorCertBytes = anchorCert.wireEncode();

            byte[] doubleBKpubHashBytesBuffer = new byte[BKpubHash.length() * 2];
            Log.d(TAG, "BKpubHash length: " + BKpubHash.length());

            byte[] BKpubHashByteArray = HexAsciiHelper.hexStringToByteArray(BKpubHash);

            System.arraycopy(BKpubHashByteArray, 0, doubleBKpubHashBytesBuffer, 0, BKpubHashByteArray.length);
            System.arraycopy(BKpubHashByteArray, 0, doubleBKpubHashBytesBuffer, BKpubHashByteArray.length, BKpubHashByteArray.length);


            ByteBuffer DoubleBKpubHashByteBuffer = ByteBuffer.wrap(doubleBKpubHashBytesBuffer);

            MessageDigest sha256;
            try {
                sha256 = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                // Don't expect this to happen.
                throw new Error
                        ("MessageDigest: SHA-256 is not supported: " + exception.getMessage());
            }

            sha256.update(DoubleBKpubHashByteBuffer);
            byte[] doubleBKpubHashDigestBytes = sha256.digest();
            Blob digestBytesBlob = new Blob(doubleBKpubHashDigestBytes);
            String doubleBKpubDigestBytesString = digestBytesBlob.toHex();
            Log.d(TAG, doubleBKpubDigestBytesString.toUpperCase());


            TlvEncoder tlvEncodedDataContent = new TlvEncoder();

            // Encode backwards.
            tlvEncodedDataContent.writeBlobTlv(130, ByteBuffer.wrap(doubleBKpubDigestBytesString.toUpperCase().getBytes()));
            Log.d(TAG, "Length of doubleBKPubHashDigestBytes: " + doubleBKpubHashDigestBytes.length);
            Log.d(TAG, "Length of tlvEncodedDataContent after writing doubleBKpubHashDigestBytes: " + tlvEncodedDataContent.getLength());

            tlvEncodedDataContent.writeNonNegativeIntegerTlv(129, devices.get(BKpubHash).token);
            Log.d(TAG, "value of token from BKpubHash: " + Long.toString(devices.get(BKpubHash).token));
            Log.d(TAG, "Length of tlvEncodedDataContent after writing token: " + tlvEncodedDataContent.getLength());

            tlvEncodedDataContent.writeBuffer(anchorCert.wireEncode().buf());
            //tlvEncodedDataContent.writeBlobTlv(6, anchorCertBytes.buf());
            //Log.d(TAG, "Length of anchorCertBytes: " + anchorCertBytes.signedSize());
            //Log.d(TAG, "Length of tlvEncodedDataContent after writing anchorCertBytes: " + tlvEncodedDataContent.getLength());
            //Log.d(TAG, anchorCert.wireEncode().buf().)

            // tlvEncodedDataContent.writeTypeAndLength(21, tlvEncodedDataContent.getLength());
            Log.d(TAG, "Length of tlvEncodedDataContent after writing type and length: " + tlvEncodedDataContent.getLength());




            //Log.d(TAG, "tlv encoded data content output to string: " + tlvEncodedDataContent.getOutput().toString());

            byte[] finalDataContentByteArray = tlvEncodedDataContent.getOutput().array();

            Blob content = new Blob(finalDataContentByteArray);

            name.appendVersion(System.currentTimeMillis());
            final Data data = new Data(name);
            data.setContent(content);

            try {
                keyChain.sign(data); // sign by controller's private key
            } catch (SecurityException e) {
                e.printStackTrace();
            } catch (TpmBackEnd.Error error) {
                error.printStackTrace();
            } catch (PibImpl.Error error) {
                error.printStackTrace();
            } catch (KeyChain.Error error) {
                error.printStackTrace();
            }

            class OneShotTask implements Runnable {
                Data data;

                OneShotTask(Data d) {
                    data = d;
                }

                public void run() {
                    try {
                        face.putData(data);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            Thread t = new Thread(new OneShotTask(data));
            t.start();
        }
    };

    private final InterestValidationFailureCallback OnFailedValidate = new InterestValidationFailureCallback() {
        @Override
        public void failureCallback(Interest interest, ValidationError error) {
            Log.d(TAG, "Interest validation failed for reason: " + error.getInfo());
        }
    };

    private final InterestValidationSuccessCallback OnSucceededValidate = new InterestValidationSuccessCallback() {
        @Override
        public void successCallback(Interest interest) {
            Log.d(TAG, "Interest validation succeeded");
        }
    };

    private final OnInterestCallback OnCertificateRequest = new OnInterestCallback() {
        @Override
        public void onInterest(Name prefix, Interest request, final Face face, long interestFilterId,
                               InterestFilter filterData) {

            // /[home-prefix]/cert/{digest of BKpub}/{CKpub}/{signature of token}/{signature by BKpri}
            Name name = request.getName();
            Log.d(TAG, "interest name: " + name.toString());
            String signatureOfToken = name.get(-3).toEscapedString();
            Log.d(TAG, "Signature of token of certificate request: " + signatureOfToken);
            Name.Component CKpub = name.get(-4);
            Log.d(TAG, "CKpub of certificate request: " + CKpub.toEscapedString());
            String BKpubHash = name.get(3).toEscapedString();
            Log.d(TAG, "BKpubhash of certificate request: " + BKpubHash);

            if (!devices.containsKey(BKpubHash)) {
                Log.d(TAG, "did not find this BKpubHash for previously scanned devices: " + BKpubHash);
                return;
            }
            else {
                Log.d(TAG, "found the BKpubHash of certificate request from previously scanned devices," +
                        "proceeding to process certificate request");
            }

            long token = devices.get(BKpubHash).token;
            Log.d(TAG, "token of device with associated BKpubHash: " + Long.toString(token));
            // TODO-5: zhiyi, please verify the signature of token here
            ByteBuffer tokenByteBuffer = ByteBuffer.allocate(Long.BYTES);
            tokenByteBuffer.putLong(devices.get(BKpubHash).token);
            boolean verification = false;

            if (VerificationHelpers.verifyInterestSignature(request, MainActivity.lastDeviceCertificate)) {
                Log.d(TAG, "verifying bootstrapping BKpri interest signature was successful");
            }
            else {
                Log.d(TAG, "verification of bootstrapping BKpri interest signature failed");
            }

            /*
            byte[] decodeData = Common.base64Decode(CKpub);
            Data keyDataPacket = new Data();
            try {
                keyDataPacket.wireDecode(new Blob(decodeData));
            } catch (EncodingException e) {
                e.printStackTrace();
            }
            */

            Data CKpubDataPacket = new Data();
            Log.d(TAG, "CKpub array outptu: " + CKpub.getValue().buf().toString());
            try {
                CKpubDataPacket.wireDecode(CKpub.getValue());
            } catch (EncodingException e) {
                e.printStackTrace();
            }

            Log.d(TAG, "after common base 64 decode");

            CertificateV2 certRequest = null;

            try {
                certRequest = new CertificateV2(CKpubDataPacket);
            } catch (CertificateV2.Error error) {
                error.printStackTrace();
            }

            CertificateV2 newCert = new CertificateV2();

            newCert.setName(certRequest.getKeyName().append("NDNCERT-IOT").appendVersion(System.currentTimeMillis()));
            newCert.setContent(certRequest.getContent());
            SigningInfo signingInfo = new SigningInfo(createdIdentity);
            signingInfo.setValidityPeriod(new ValidityPeriod(System.currentTimeMillis(),
                    System.currentTimeMillis() + TimeUnit.DAYS.toMillis(10)));
            try {
                keyChain.sign(newCert, createdIdentity.getDefaultKey().getDefaultCertificate().getName());
            } catch (SecurityException e) {
                e.printStackTrace();
            } catch (Pib.Error error) {
                error.printStackTrace();
            } catch (PibImpl.Error error) {
                error.printStackTrace();
            }

            Data data = new Data(new Name(name).appendVersion(System.currentTimeMillis()));
            data.setContent(newCert.wireEncode());
            try {
                keyChain.sign(data, createdIdentity.getDefaultKey().getDefaultCertificate().getName());
            } catch (SecurityException e) {
                e.printStackTrace();
            } catch (Pib.Error error) {
                error.printStackTrace();
            } catch (PibImpl.Error error) {
                error.printStackTrace();
            }


            Log.d(TAG, "testing before the data oneshot task");
            class OneShotTask implements Runnable {
                Data data;

                OneShotTask(Data d) {
                    data = d;
                }

                public void run() {
                    try {
                        face.putData(data);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            Thread t = new Thread(new OneShotTask(data));
            t.start();
        }
    };

    private final OnRegisterSuccess OnPrefixRegisterSuccess = new OnRegisterSuccess() {
        @Override
        public void onRegisterSuccess(Name prefix, long registeredPrefixId) {
            Log.d(TAG, "successfully registered data prefix: " + prefix);
        }
    };

    private final OnRegisterFailed OnPrefixRegisterFailed = new OnRegisterFailed() {
        @Override
        public void onRegisterFailed(Name prefix) {
            Log.d(TAG, "we failed to register the data prefix: " + prefix);
        }
    };

    private void initializeKeyChain() {

        Log.d(TAG, "initializing keychain");
        keyChain = null;
        try {
            keyChain = new KeyChain("pib-memory:", "tpm-memory:");
        } catch (KeyChain.Error error) {
            error.printStackTrace();
        } catch (PibImpl.Error error) {
            error.printStackTrace();
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        createdIdentity = null;
        try {
            createdIdentity = keyChain.createIdentityV2(new Name("/ucla/eiv396"));
            //createdIdentity = keyChain.createIdentityV2(new Name("/ucla/eiv396"), new EcKeyParams());

            if (createdIdentity == null) {
                Log.d(TAG, "created identity for initialize was null");
            }
            else
                Log.d(TAG, createdIdentity.getName().toUri());
            try {
                Log.d(TAG, "Check default " + keyChain.getDefaultCertificateName());
            } catch (SecurityException e) {
                e.printStackTrace();
            }
        } catch (TpmBackEnd.Error error) {
            error.printStackTrace();
        } catch (PibImpl.Error error) {
            error.printStackTrace();
        } catch (Tpm.Error error) {
            error.printStackTrace();
        } catch (KeyChain.Error error) {
            error.printStackTrace();
        } catch (Pib.Error error) {
            error.printStackTrace();
        }

        try {
            controllerCertificate = createdIdentity.getDefaultKey().getDefaultCertificate();
        } catch (Pib.Error error) {
            error.printStackTrace();
        } catch (PibImpl.Error error) {
            error.printStackTrace();
        }

        if (controllerCertificate == null) {
            Log.d(TAG, "controller certificate was null");
        }


        if (keyChain.getIsSecurityV1()) {
            Log.d(TAG, "keychain is security v1");
        }
        else {
            Log.d(TAG, "keychain is security v2");
        }

        /*
        try {
            //keyChain.setDefaultIdentity(createdIdentity);
        } catch (PibImpl.Error error) {
            error.printStackTrace();
        } catch (Pib.Error error) {
            error.printStackTrace();
        }
        */

        //Log.d(TAG, "initializing keychain");
        //MemoryIdentityStorage identityStorage = new MemoryIdentityStorage();
        //MemoryPrivateKeyStorage privateKeyStorage = new MemoryPrivateKeyStorage();
        //IdentityManager identityManager = new IdentityManager(identityStorage, privateKeyStorage);
        //keyChain = new KeyChain(identityManager);
        keyChain.setFace(face);

        try {
            Log.d(TAG, "Check default 3 " + keyChain.getDefaultCertificateName());
        } catch (SecurityException e) {
            e.printStackTrace();
        }

        try {
            Log.d(TAG, keyChain.getDefaultCertificateName().toUri());
            face.setCommandSigningInfo(keyChain, keyChain.getDefaultCertificateName());
            Log.d(TAG, "line after set command signing");
        } catch (SecurityException e) {
            Log.d(TAG, "exception " + e.getMessage());
            e.printStackTrace();
        }


    }

    private void setCommandSigningInfo() {
        Log.d(TAG, "setting command signing info");

        Log.d(TAG, "another print statement inside set command signing info");

        if (createdIdentity == null) {
            Log.d(TAG, "set command signing info check: created identity was null");
        }
        else {
            Log.d(TAG, "created identity was not null for set command signing info");
        }

        if (keyChain == null) {
            Log.d(TAG, "set command signing info check: keychain was null");
        }
        else {
            Log.d(TAG, "keychain was not null for set command signing info");
        }

/*
        Name defaultCertificateName;
        try {
            defaultCertificateName = keyChain.getDefaultCertificateName();
        } catch (SecurityException e) {
            Log.d(TAG, "unable to get default certificate name");

            // NOTE: This is based on apps-NDN-Whiteboard/helpers/Utils.buildTestKeyChain()...
            Name testIdName = new Name("/test/identity");
            try {
                defaultCertificateName = keyChain.createIdentityAndCertificate(testIdName);
                keyChain.getIdentityManager().setDefaultIdentity(testIdName);
                Log.d(TAG, "created default ID: " + defaultCertificateName.toString());
            } catch (SecurityException e2) {
                defaultCertificateName = new Name("/controller/certificate/name");
            }
        }

        face.setCommandSigningInfo(keyChain, defaultCertificateName);

*/

/*
        try {
            Log.d(TAG, keyChain.getDefaultCertificateName().toUri());
            face.setCommandSigningInfo(keyChain, keyChain.getDefaultCertificateName());
            Log.d(TAG, "line after set command signing");
        } catch (SecurityException e) {
            Log.d(TAG, "exception " + e.getMessage());
            e.printStackTrace();
        }
        */



    }

    public void expressInterest(String dataName) {

        Log.d(TAG + " actual string data ", dataName);

        Name name = new Name(dataName);

        Log.d(TAG, name.toString());

        expressInterest(name);
    }

    private void expressInterest(Name dataName) {
        Log.d(TAG, "expressing interest for " + dataName.toString());

        class OneShotTask implements Runnable {
            Name name;

            OneShotTask(Name n) {
                name = n;
            }

            public void run() {
                try {
                    Interest interest = new Interest(name);

                    interest.setMustBeFresh(true);

                    face.expressInterest(interest, OnReceivedData,
                            OnInterestTimeout, OnInterestNack);

                } catch (IOException e) {
                    Log.d(TAG, "failure when responding to data interest: " + e.toString());
                }
            }
        }
        Thread t = new Thread(new OneShotTask(dataName));
        t.start();

    }

    private final Thread networkThread = new Thread(new Runnable() {

        @Override
        public void run () {
            Log.d(TAG, "network thread started");
            try {
                face = new Face("localhost");
                initializeKeyChain();
                if (keyChain == null) {
                    Log.d(TAG, "keychain in network thread run was null");
                }
                else {
                    Log.d(TAG, "keychain in network thread run was not null");
                }
                try {
                    Log.d(TAG, "Check default 2 " + keyChain.getDefaultCertificateName());
                } catch (SecurityException e) {
                    e.printStackTrace();
                }
                setCommandSigningInfo();
                registerDataPrefix();
            } catch (Exception e) {
                //raiseError("error during network thread initialization",
                //ErrorCode.OTHER_EXCEPTION, e);
            }
            while (!alreadyRunning) {
                try {
                    face.processEvents();
                    Thread.sleep(100); // avoid hammering the CPU
                } catch (IOException e) {
                    //raiseError("error in processEvents loop", ErrorCode.NFD_PROBLEM, e);
                } catch (Exception e) {
                    //raiseError("error in processEvents loop", ErrorCode.OTHER_EXCEPTION, e);
                }
            }
            doFinalCleanup();
            //handleAnyRaisedError();
            Log.d(TAG, "network thread stopped");


        }
    });

    private final IBinder mBinder = new NFDService.LocalBinder();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {

        Log.d(TAG, "onbind for NFDService got called");
        devices = new HashMap<>();

        return mBinder;
    }

    public class LocalBinder extends Binder {
        NFDService getService() {
            return NFDService.this;
        }
    }

    public static IntentFilter getIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(NFD_STOPPED);
        filter.addAction(FACE_CLOSED);
        filter.addAction(INTEREST_RECEIVED);
        filter.addAction(DATA_RECEIVED);
        filter.addAction(INTEREST_SENT);
        filter.addAction(DATA_SENT);
        filter.addAction(INTEREST_CONNECT_GET_SERVICES_RECEIVED);
        filter.addAction(INTEREST_DISCOVER_RECEIVED);
        filter.addAction(INTEREST_DISCONNECT_RECEIVED);
        filter.addAction(INTEREST_CHARACTERISTIC_ACTION_RECEIVED);
        return filter;
    }

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private void doFinalCleanup() {
        Log.d(TAG, "cleaning up and resetting service...");
        if (face != null) face.shutdown();
        face = null;
        Log.d(TAG, "service cleanup/reset complete");
    }



    private final OnData OnReceivedData = new OnData() {
        @Override
        public void onData(Interest interest, Data data) {
            Name name = data.getName();
            String content = data.getContent().toString();
            Log.d(TAG, "received data for " + name);

            Intent intent = new Intent(DATA_RECEIVED);
            intent.putExtra("STRING_DATA", content);
            sendBroadcast(intent);
        }
    };

    private final OnTimeout OnInterestTimeout = new OnTimeout() {
        @Override
        public void onTimeout(Interest interest) {
            Name name = interest.getName();
            sendBroadcast(new Intent(INTEREST_TIMEOUT));
            Log.d(TAG, "timed out waiting for " + name);
        }
    };

    private final OnNetworkNack OnInterestNack = new OnNetworkNack() {
        @Override
        public void onNetworkNack(Interest interest, NetworkNack networkNack) {
            Name name = interest.getName();
            sendBroadcast(new Intent(INTEREST_NACK));
            Log.d(TAG, "received NACK for " + name);
        }
    };

    @Override
    public boolean onUnbind(Intent intent) {

        return super.onUnbind(intent);
    }

    private static ByteBuffer
    toBuffer(int[] array)
    {
        ByteBuffer result = ByteBuffer.allocate(array.length);
        for (int i = 0; i < array.length; ++i)
            result.put((byte)(array[i] & 0xff));

        result.flip();
        return result;
    }
}