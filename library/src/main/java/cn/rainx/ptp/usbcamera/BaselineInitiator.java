// Copyright 2000 by David Brownell <dbrownell@users.sourceforge.net>
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed epIn the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
package cn.rainx.ptp.usbcamera;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import cn.rainx.ptp.interfaces.FileAddedListener;
import cn.rainx.ptp.interfaces.FileDownloadedListener;
import cn.rainx.ptp.interfaces.FileTransferListener;
import cn.rainx.ptp.params.SyncParams;

/**
 * This initiates interactions with USB devices, supporting only
 * mandatory PTP-over-USB operations; both
 * "push" and "pull" modes are supported.  Note that there are some
 * operations that are mandatory for "push" responders and not "pull"
 * ones, and vice versa.  A subclass adds additional standardized
 * operations, which some PTP devices won't support.  All low
 * level interactions with the device are done by this class,
 * including especially error recovery.
 *
 * <p> The basic sequence of operations for any PTP or ISO 15470
 * initiator (client) is:  acquire the device; wrap it with this
 * driver class (or a subclass); issue operations;
 * close device.  PTP has the notion
 * of a (single) session with the device, and until you have an open
 * session you may only invoke {@link #getDeviceInfo} and
 * {@link #openSession} operations.  Moreover, devices may be used
 * both for reading images (as from a camera) and writing them
 * (as to a digital picture frame), depending on mode support.
 *
 * <p> Note that many of the IOExceptions thrown here are actually
 * going to be <code>usb.core.PTPException</code> values.  That may
 * help your application level recovery processing.  You should
 * assume that when any IOException is thrown, your current session
 * has been terminated.
 *
 *
 * @version $Id: BaselineInitiator.java,v 1.17 2001/05/30 19:33:43 dbrownell Exp $
 * @author David Brownell
 *
 * This class has been reworked by ste epIn order to make it compatible with
 * usbjava2. Also, this is more a derivative work than just an adaptation of the
 * original version. It has to serve the purposes of usbjava2 and cameracontrol.
 */
public class BaselineInitiator extends NameFactory implements Runnable {

    ///////////////////////////////////////////////////////////////////
    // USB Class-specific control requests; from Annex D.5.2
    private static final byte CLASS_CANCEL_REQ        = (byte) 0x64;
    private static final byte CLASS_GET_EVENT_DATA    = (byte) 0x65;
    private static final byte CLASS_DEVICE_RESET      = (byte) 0x66;
    private static final byte CLASS_GET_DEVICE_STATUS = (byte) 0x67;
    protected static final int  DEFAULT_TIMEOUT 		  = 1000; // ms

    final static boolean DEBUG = false;
    final static boolean TRACE = false;
	public static final String TAG = "BaselineInitiator";
    
    public UsbDevice device;
    protected UsbInterface intf;
    protected UsbEndpoint epIn;
    protected int                    	inMaxPS;
    protected UsbEndpoint epOut;
    protected UsbEndpoint epEv;
    protected int                        intrMaxPS;
    protected Session                	session;
    protected DeviceInfo             	info;
    protected Random                    rand = new Random();
    public UsbDeviceConnection mConnection = null; // must be initialized first!
    	// mUsbManager = (UsbManager)getSystemService(Context.USB_SERVICE);


    protected List<FileDownloadedListener> fileDownloadedListenerList = new ArrayList<FileDownloadedListener>();
    protected List<FileTransferListener> fileTransferListenerList = new ArrayList<FileTransferListener>();



    /// 文件下载路径
    protected String fileDownloadPath;


    // running polling pollingThread
    Thread pollingThread = null;

    // 同步触发模式
    protected int syncTriggerMode = SyncParams.SYNC_TRIGGER_MODE_EVENT;



    // 在open session 的时候，有的时候会出现相机设备已经open session 了，但是手机并不知道这个消息，这个时候需
    // 要重新关闭之后再进行openSession操作。
    protected boolean autoCloseSessionIfSessionAlreadyOpenWhenOpenSession = true;


    // 提供一个默认的构造函数，供子类继承时使用
    protected BaselineInitiator() { };

    /**
     * Constructs a class driver object, if the device supports
     * operations according to Annex D of the PTP specification.
     *
     * @param dev the first PTP interface will be used
     * @exception IllegalArgumentException if the device has no
     *	Digital Still Imaging Class or PTP interfaces
     */
    public BaselineInitiator(UsbDevice dev, UsbDeviceConnection connection) throws PTPException {
        if (connection == null) {
            throw new PTPException ("Connection = null");//IllegalArgumentException();
        }
    	this.mConnection = connection;
            if (dev == null) {
                throw new PTPException ("dev = null");//IllegalArgumentException();
            }
            session = new Session();
            this.device = dev;
            intf = findUsbInterface (dev);


            if (intf == null) {
            //if (usbInterface == null) {
                throw new PTPException("No PTP interfaces associated to the device");
            }

    		for (int i = 0; i < intf.getEndpointCount(); i++) {
    			UsbEndpoint ep = intf.getEndpoint(i);
    			if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
    				if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
    					epOut = ep;
    				} else {
    					epIn = ep;
    				}
    			}
    			if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT){
    				epEv = ep;
    			}
    		}
            endpointSanityCheck();
            inMaxPS = epOut.getMaxPacketSize();
            intrMaxPS = epIn.getMaxPacketSize();

            reset();
            if (getClearStatus() != Response.OK
                    && getDeviceStatus(null) != Response.OK) {
                throw new PTPException("can't init");
            }

            Log.d(TAG, "trying getDeviceInfoUncached");
            // get info to sanity check later requests
            info = getDeviceInfoUncached(); 

            // set up to use vendor extensions, if any
            if (info.vendorExtensionId != 0) {
                info.factory = updateFactory(info.vendorExtensionId);
            }
            session.setFactory((NameFactory) this);

    }

    
	// searches for an interface on the given USB device, returns only class 6  // From androiddevelopers ADB-Test
	protected UsbInterface findUsbInterface(UsbDevice device) {
		//Log.d (TAG, "findAdbInterface " + device.getDeviceName());
		int count = device.getInterfaceCount();
		for (int i = 0; i < count; i++) {
			UsbInterface intf = device.getInterface(i);
			Log.d (TAG, "Interface " +i + " Class " +intf.getInterfaceClass() +" Prot " +intf.getInterfaceProtocol());
			if (intf.getInterfaceClass() == 6
					//255 && intf.getInterfaceSubclass() == 66 && intf.getInterfaceProtocol() == 1
					) {
				return intf;
			}
		}
		return null;
	}
    
    
    /**
     * @return the device
     */
    public UsbDevice getDevice() {
        return device;
    }

        /**
     * Returns the last cached copy of the device info, or returns
     * a newly cached copy.
     * @see #getDeviceInfoUncached
     */
    public DeviceInfo getDeviceInfo() throws PTPException {
        if (info == null) {
            return getDeviceInfoUncached();
        }
        return info;
    }

    /**
     * Sends a USB level CLASS_DEVICE_RESET control message.
     * All PTP-over-USB devices support this operation.
     * This is documented to clear stalls and camera-specific suspends,
     * flush buffers, and close the current session.
     *
     */
    public void reset() throws PTPException 
    {
    	if (mConnection == null) throw new PTPException("No Connection");
    	
    	mConnection.controlTransfer(
                (int) ( UsbConstants.USB_DIR_OUT      |
                		UsbConstants.USB_TYPE_CLASS        /* |
                        UsbConstants.RECIPIENT_INTERFACE */),
                CLASS_DEVICE_RESET,
                0,
                0,
                new byte[0],
                0,
                DEFAULT_TIMEOUT //,
                //false
            );

            session.close();
    }

    /**
     * Issues an OpenSession command to the device; may be used
     * with all responders.  PTP-over-USB doesn't seem to support
     * multisession operations; you must close a session before
     * opening a new one.
     */
    public void openSession() throws PTPException {
        Command command;
        Response response;

        synchronized (session) {
            command = new Command(Command.OpenSession, session,
                    session.getNextSessionID());
            response = transactUnsync(command, null);
            switch (response.getCode()) {
                case Response.OK:
                    session.open();
                    pollingThread = new Thread(this);
                    pollingThread.start();
                    return;
                case Response.SessionAlreadyOpen:
                    // 进行一次断开重试操作 ： 目前还没有测试该功能
                    if (autoCloseSessionIfSessionAlreadyOpenWhenOpenSession){
                        closeSession();
                        session = new Session();
                        command = new Command(Command.OpenSession, session,
                                session.getNextSessionID());
                        response = transactUnsync(command, null);
                        if (response.getCode() == Response.OK) {
                            session.open();
                            pollingThread = new Thread(this);
                            pollingThread.start();
                        }
                    }

                 default:
                    throw new PTPOpenSessionException(response.toString(), response.getCode());
            }
        }
    }

    /**
     * Issues a CloseSession command to the device; may be used
     * with all responders.
     */
    public void closeSession() throws PTPException {
        Response response;

        synchronized (session) {
            // checks for session already open
            response = transact0(Command.CloseSession, null);
            switch (response.getCode()) {
                case Response.SessionNotOpen:
                    if (DEBUG) {
                        System.err.println("close unopen session?");
                    }
                // FALLTHROUGH
                case Response.OK:
                    session.close();
                    return;
                default:
                    throw new PTPException(response.toString());
            }
        }
    }

    /**
     * Closes the session (if active) and releases the device.
     *
     * @throws PTPException
     */
    public void close() throws PTPException {
        // stop and close polling thead;
        if (pollingThread != null) {
            pollingThread.interrupt();
            pollingThread = null;
        }

        if (isSessionActive()) {
            try {
                closeSession();
            } catch (PTPException ignore) {
                //
                // Is we cannot close the session, there is nothing we can do
                //
            } catch (IllegalArgumentException ignore) {
                //
            }
        }

        try {
        	if (mConnection != null && intf != null) mConnection.releaseInterface(intf);
            if (mConnection != null) mConnection.close();
        	device = null;
            info = null;
        } catch (Exception ignore) {
            throw new PTPException("Unable to close the USB device");
        }
    }


    
    /**
     * @return true if the current session is active, false otherwise
     */
    public boolean isSessionActive() {
        synchronized (session) {
            return session.isActive();
        }
    }

    // ------------------------------------------------------- Protected methods

    ///////////////////////////////////////////////////////////////////
    /**
     * Performs a PTP transaction, passing zero command parameters.
     * @param code the command code
     * @param data data to be sent or received; or null
     * @return response; check for code Response.OK before using
     *	any positional response parameters
     */
    protected Response transact0(int code, Data data)
    throws PTPException {
        synchronized (session) {
            Command command = new Command(code, session);
            return transactUnsync(command, data);
        }
    }

    /**
     * Performs a PTP transaction, passing one command parameter.
     * @param code the command code
     * @param data data to be sent or received; or null
     * @param p1 the first positional parameter
     * @return response; check for code Response.OK before using
     *	any positional response parameters
     */
    protected Response transact1(int code, Data data, int p1)
    throws PTPException {
        synchronized (session) {
            Command command = new Command(code, session, p1);
            return transactUnsync(command, data);
        }
    }

    /**
     * Performs a PTP transaction, passing two command parameters.
     * @param code the command code
     * @param data data to be sent or received; or null
     * @param p1 the first positional parameter
     * @param p2 the second positional parameter
     * @return response; check for code Response.OK before using
     *	any positional response parameters
     */
    protected Response transact2(int code, Data data, int p1, int p2)
            throws PTPException {
        synchronized (session) {
            Command command = new Command(code, session, p1, p2);
            return transactUnsync(command, data);
        }
    }

    /**
     * Performs a PTP transaction, passing three command parameters.
     * @param code the command code
     * @param data data to be sent or received; or null
     * @param p1 the first positional parameter
     * @param p2 the second positional parameter
     * @param p3 the third positional parameter
     * @return response; check for code Response.OK before using
     *	any positional response parameters
     */
    protected Response transact3(int code, Data data, int p1, int p2, int p3)
            throws PTPException {
        synchronized (session) {
            Command command = new Command(code, session, p1, p2, p3);
            return transactUnsync(command, data);
        }
    }

    // --------------------------------------------------------- Private methods

        // like getDeviceStatus(),
    // but clears stalled endpoints before returning
    // (except when exceptions are thrown)
    // returns -1 if device wouldn't return OK status
    public int getClearStatus() throws PTPException {
        Buffer buf = new Buffer(null, 0);
        int retval = getDeviceStatus(buf);

        // any halted endpoints to clear?  (always both)
        if (buf.length != 4) {
            while ((buf.offset + 4) <= buf.length) {
                int ep = buf.nextS32();
                if (epIn.getAddress() == ep) {
                    if (TRACE) {
                        System.err.println("clearHalt epIn");
                    }
                    clearHalt(epIn);
                } else if (epOut.getAddress() == ep) {
                    if (TRACE) {
                        System.err.println("clearHalt epOut");
                    }
                    clearHalt(epOut);
                } else {
                    if (DEBUG || TRACE) {
                        System.err.println("?? halted EP: " + ep);
                    }
                }
            }

            // device must say it's ready
            int status = Response.Undefined;

            for (int i = 0; i < 10; i++) {
                try {
                    status = getDeviceStatus(null);
                } catch (PTPException x) {
                    if (DEBUG) {
                        x.printStackTrace();
                    }
                }
                if (status == Response.OK) {
                    break;
                }
                if (TRACE) {
                    System.err.println("sleep; status = "
                            + getResponseString(status));
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException x) {
                }
            }
            if (status != Response.OK) {
                retval = -1;
            }
        } else {
            if (TRACE) {
                System.err.println("no endpoints halted");
            }
        }
        return retval;
    }

    // returns Response.OK, Response.DeviceBusy, etc
    // per fig D.6, response may hold stalled endpoint numbers
    protected int getDeviceStatus(Buffer buf)
    throws PTPException {

    	if (mConnection == null) throw new PTPException("No Connection");
    	
            byte[] data = new byte[33];
            
        	mConnection.controlTransfer(
//            device.controlMsg(
                (int) (UsbConstants.USB_DIR_IN        |
                		UsbConstants.USB_TYPE_CLASS    /*     |
                		UsbConstants.RECIPIENT_INTERFACE*/),
                CLASS_GET_DEVICE_STATUS,
                0,
                0,
                data,
                data.length, // force short reads
                DEFAULT_TIMEOUT //,
                //false
            );

            if (buf == null) {
                buf = new Buffer(data);
            } else {
                buf.data = data;
            }
            buf.offset = 4;
            buf.length = buf.getU16(0);
            if (buf.length != buf.data.length) {
                //throw new PTPException("DeviceStatus error, Buffer length wrong!");
            }

            return buf.getU16(2);

    }

    // add event listener
    // rm event listener
    ///////////////////////////////////////////////////////////////////
    // mandatory for all responders
    /**
     * Issues a GetDeviceInfo command to the device; may be used
     * with all responders.  This is the only generic PTP command
     * that may be issued both inside or outside of a session.
     */
    protected DeviceInfo getDeviceInfoUncached()
    throws PTPException {
        DeviceInfo data = new DeviceInfo(this);
        Response response;

        synchronized (session) {
            Command command;
            command = new Command(Command.GetDeviceInfo, session);
            response = transactUnsync(command, data);

        }

        switch (response.getCode()) {
            case Response.OK:
                info = data;
                return data;
            default:
                throw new PTPException(response.toString());
        }
    }

    ///////////////////////////////////////////////////////////////////
    // INVARIANTS:
    // - caller is synchronized on session
    // - on return, device is always epIn idle/"command ready" state
    // - on return, session was only closed by CloseSession
    // - on PTPException, device (and session!) has been reset
    public Response transactUnsync(Command command, Data data)
    throws PTPException {
        if (!"command".equals(command.getBlockTypeName(command.getBlockType()))) {
            throw new IllegalArgumentException(command.toString());
        }

        // sanity checking
        int opcode = command.getCode();

        if (session.isActive()) {
            if (Command.OpenSession == opcode) {
                throw new IllegalStateException("session already open");
            }
        } else {
            if (Command.GetDeviceInfo != opcode
                    && Command.OpenSession != opcode) {
                throw new IllegalStateException("no session");
            }
        }

        // this would be UnsupportedOperationException ...
        // except that it's not available on jdk 1.1
        if (info != null && !info.supportsOperation(opcode)) {
            throw new UnsupportedOperationException(command.getCodeName(opcode));
        }

        // ok, then we'll really talk to the device
        Response response;
        boolean abort = true;


            if (TRACE) {
                System.err.println(command.toString());
            }
            int lenC = mConnection.bulkTransfer(epOut, command.data , command.length , DEFAULT_TIMEOUT);
			Log.d(TAG, "Command " +  command._getOpcodeString(command.getCode()) + " bytes sent " +lenC);

            // may need to terminate request with zero length packet
            if ((command.length % epOut.getMaxPacketSize()) == 0) {
				lenC = mConnection.bulkTransfer(epOut, command.data, 0, DEFAULT_TIMEOUT);

            }

            // data exchanged?
            // errors or cancel (another pollingThread) will stall both EPs
            if (data != null) {

                // write data?
                if (!data.isIn()) {
//                	Log.d(TAG, "Start Write Data");

                    data.offset = 0;
                    data.putHeader(data.getLength(), 2 /*Data*/, opcode,
                            command.getXID());

                    if (TRACE) {
                        System.err.println(data.toString());
                    }


                    byte[] bytes = data.getData();//new byte [data.length];
//    				Log.d(TAG, "send Data");
                    int len = mConnection.bulkTransfer(epOut, bytes , bytes.length, DEFAULT_TIMEOUT);
//    				Log.d(TAG, "bytes sent " +len);
                    if (len < 0) {
                    	throw new PTPException("short: " + len);
                    }

                        //stream.write(data.data, 0, data.length);
                        if ((data.length % epOut.getMaxPacketSize()) == 0) {
//                        	Log.d(TAG, "send 0 Data");
                        	mConnection.bulkTransfer(epOut, bytes , 0, DEFAULT_TIMEOUT);
                            //stream.write(data.data, 0, 0);
//                        }
                    }

                    // read data?
                } else {
// Log.d(TAG, "Start Read Data");
					byte readBuffer[] = new byte[inMaxPS];
					int readLen = 0;
					readLen = mConnection.bulkTransfer(epIn, readBuffer, inMaxPS,
							DEFAULT_TIMEOUT);
                    if (readLen == 0) {
                        // rainx note: 有的时候，端点会返回空包，这个时候需要再次发送请求
                        Log.d(TAG, "rainx note: 有的时候，端点会返回空包，这个时候需要再次发送请求 ");
                        readLen = mConnection.bulkTransfer(epIn, readBuffer, inMaxPS,
                                DEFAULT_TIMEOUT);
                    }
					data.data = readBuffer;
					data.length = readLen;
					if (!"data".equals(data.getBlockTypeName(data.getBlockType()))
							|| data.getCode() != command.getCode()
							|| data.getXID() != command.getXID()) {
                        if (data.getLength() == 0) {
                            readLen = mConnection.bulkTransfer(epIn, readBuffer, inMaxPS,
                                    DEFAULT_TIMEOUT);
                            data.data = readBuffer;
                            data.length = readLen;

                            Log.d(TAG, "read a unkonwn pack , read again:" + byteArrayToHex(data.data));
                        }
						throw new PTPException("protocol err 1, " + data +
                            "\n data:" + byteArrayToHex(data.data));
					}
					
					int totalLen = data.getLength();
					if (totalLen > readLen) {
						ByteArrayOutputStream dataStream = new ByteArrayOutputStream(
								totalLen);
					
						dataStream.write(readBuffer, 0, readLen);
						
						int remaining = totalLen - readLen;
						while (remaining > 0) {
							int toRead = (remaining > inMaxPS )? inMaxPS : remaining;
							readLen = mConnection.bulkTransfer(epIn, readBuffer, toRead,
									DEFAULT_TIMEOUT);
							dataStream.write(readBuffer, 0, readLen);
							remaining -= readLen;
						}
						
						data.data = dataStream.toByteArray();
						data.length = data.length;
					}
                    data.parse();
                }
            }

            // (short) read the response
            // this won't stall anything
            byte buf[] = new byte[inMaxPS];
            Log.d(TAG, "read response");
            int len = mConnection.bulkTransfer(epIn, buf ,inMaxPS , DEFAULT_TIMEOUT);//device.getInputStream(epIn).read(buf);
            Log.d(TAG, "received data bytes: " +len);
            
            // ZLP terminated previous data?
            if (len == 0) {
                len = mConnection.bulkTransfer(epIn, buf ,inMaxPS , DEFAULT_TIMEOUT);// device.getInputStream(epIn).read(buf);
//                Log.d(TAG, "received data bytes: " +len);
            }

            response = new Response(buf, len, this);
            if (TRACE) {
                System.err.println(response.toString());
            }

            abort = false;
            return response;


    }

    protected void endpointSanityCheck() throws PTPException {
        if (epIn == null) {
            throw new PTPException("No input end-point found!");
        }

        if (epOut == null) {
            throw new PTPException("No output end-point found!");
        }

        if (epEv == null) {
            throw new PTPException("No input interrupt end-point found!");
        }
        if (DEBUG){
    		Log.d(TAG, "Get: "+device.getInterfaceCount()+" Other: "+device.getDeviceName());
    		Log.d(TAG, "\nClass: "+intf.getInterfaceClass()+","+intf.getInterfaceSubclass()+","+intf.getInterfaceProtocol()
    			      + "\nIendpoints: "+epIn.getMaxPacketSize()+ " Type "+ epIn.getType() + " Dir "+epIn.getDirection()); //512 2 USB_ENDPOINT_XFER_BULK USB_DIR_IN 
    		Log.d(TAG, "\nOendpoints: "+epOut.getMaxPacketSize()+ " Type "+ epOut.getType() + " Dir "+epOut.getDirection()); //512 2 USB_ENDPOINT_XFER_BULK USB_DIR_OUT
    		Log.d(TAG, "\nEendpoints: "+epEv.getMaxPacketSize()+ " Type "+ epEv.getType() + " Dir "+epEv.getDirection()); //8,3 USB_ENDPOINT_XFER_INT USB_DIR_IN
        	
        }
    }

    private void clearHalt(UsbEndpoint e) {
        //
        // TODO: implement clearHalt of an endpoint
        //
    }
    //Ash code


	public void write(byte[] data, int length, int timeout)
	{
		Log.d(TAG,"Sending command");
		mConnection.bulkTransfer(epOut, data , length , timeout);
		
	}




    /**
     * 实现一个类似Android MtpDevice API里面的imoprtFile的功能
     *
     *
     * ref https://android.googlesource.com/platform/frameworks/av/+/master/media/mtp/MtpDevice.cpp
     *
     * bool MtpDevice::readData(ReadObjectCallback callback,
     *      const uint32_t* expectedLength,
     *      uint32_t* writtenSize,
     *      void* clientData)
     *
     * Copies the data for an object to a file in external storage.
     * This call may block for an arbitrary amount of time depending on the size
     * of the data and speed of the devices.
     *
     * @param objectHandle handle of the object to read
     * @param destPath path to destination for the file transfer.
     *      This path should be in the external storage as defined by
     *      {@link android.os.Environment#getExternalStorageDirectory}
     * @return true if the file transfer succeeds
     */
    public boolean importFile(int objectHandle, String destPath)
            throws PTPException, IOException {

        File outputFile = new File(destPath);
        FileOutputStream outputStream;
        try {
            outputStream = new FileOutputStream(outputFile);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            throw new PTPException("can not import file since the destPath is hit FileNotFoundException");
        }

        synchronized (session) {
            long startDownloadAt = System.currentTimeMillis();
            // step 1 发送指令阶段
            Command command = new Command(Command.GetObject, session, objectHandle);
            if (!session.isActive())
                throw new IllegalStateException("no session");

            // this would be UnsupportedOperationException ...
            // except that it's not available on jdk 1.1
            if (info != null && !info.supportsOperation(Command.GetObject)) {
                throw new UnsupportedOperationException(command.getCodeName(Command.GetObject));
            }

            // ok, then we'll really talk to the device
            Response response;
            boolean abort = true;
            int lenC = mConnection.bulkTransfer(epOut, command.data , command.length , DEFAULT_TIMEOUT);

            // may need to terminate request with zero length packet
            if ((command.length % epOut.getMaxPacketSize()) == 0) {
                lenC = mConnection.bulkTransfer(epOut, command.data, 0, DEFAULT_TIMEOUT);
            }
            // step2 读取数据阶段
            byte readBuffer[] = new byte[inMaxPS];
            int readLen = 0;
            readLen = mConnection.bulkTransfer(epIn, readBuffer, inMaxPS,
                    DEFAULT_TIMEOUT);

            // 获取第一块data buffer
            Data data = new Data(this);
            data.data = readBuffer;
            data.length = readLen;

            // If object size 0 byte, the remote device may reply a response packet without sending any data
            // packets.
            if (data.getBlockType() == Container.BLOCK_TYPE_RESPONSE) {
                response = new Response(data.data, this);
                return response.getCode() == Response.OK;
            }

            if (!"data".equals(data.getBlockTypeName(data.getBlockType()))
                    || data.getCode() != command.getCode()
                    || data.getXID() != command.getXID()) {
                throw new PTPException("protocol err 1, " + data);
            }

            int fullLength = data.getLength();

            if (fullLength < Container.HDR_LEN) {
                Log.v("ptp-error", "fullLength is too short: " + fullLength);
                return false;
            }

            int length = fullLength - Container.HDR_LEN;
            int offset = 0;
            int initialDataLength = data.length - Container.HDR_LEN;

            if (initialDataLength > 0) {
                outputStream.write(data.getData(), Container.HDR_LEN, initialDataLength);
                offset += initialDataLength;
                for(FileTransferListener fileTransferListener: fileTransferListenerList) {
                    fileTransferListener.onFileTranster(BaselineInitiator.this, objectHandle, length, initialDataLength);
                }
            }

            int remaining = fullLength - readLen;
            while (remaining > 0) {
                int toRead = (remaining > inMaxPS )? inMaxPS : remaining;
                readLen = mConnection.bulkTransfer(epIn, readBuffer, toRead,
                        DEFAULT_TIMEOUT);
                if (readLen > inMaxPS) {
                    // should not be true; but it happens
                    Log.d(TAG, "readLen " + readLen + " is bigger than inMaxPS:" + inMaxPS);
                    readLen = inMaxPS;
                }
                outputStream.write(readBuffer, 0, readLen);
                remaining -= readLen;

                for(FileTransferListener fileTransferListener: fileTransferListenerList) {
                    fileTransferListener.onFileTranster(this, objectHandle, length, length-remaining);
                }
            }
            outputStream.close();
            // step3 接收response阶段
            response = readResponse();
            if (response != null && response.getCode() == Response.OK) {
                long downloadDuring = System.currentTimeMillis() - startDownloadAt;
                for(FileDownloadedListener fileDownloadedListener: fileDownloadedListenerList) {
                    fileDownloadedListener.onFileDownloaded(this, objectHandle, outputFile, downloadDuring);
                }
                return true;
            }
        }
        return false;
    }

    public Response readResponse() {
        Response response;
        byte buf[] = new byte[inMaxPS];
//            Log.d(TAG, "read response");
        int len = mConnection.bulkTransfer(epIn, buf ,inMaxPS , DEFAULT_TIMEOUT);//device.getInputStream(epIn).read(buf);
//            Log.d(TAG, "received data bytes: " +len);

        // ZLP terminated previous data?
        if (len == 0) {
            len = mConnection.bulkTransfer(epIn, buf ,inMaxPS , DEFAULT_TIMEOUT);// device.getInputStream(epIn).read(buf);
//                Log.d(TAG, "received data bytes: " +len);
        }

        response = new Response(buf, len, this);
        if (TRACE) {
            System.err.println(response.toString());
        }

        return response;
    }



    public static String byteArrayToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for(byte b: a)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }



    public void setFileDownloadedListener(FileDownloadedListener l) {
        if (!fileDownloadedListenerList.contains(l)) {
            fileDownloadedListenerList.add(l);
        }
    }

    public void setFileTransferListener(FileTransferListener l) {
        if (!fileTransferListenerList.contains(l)) {
            fileTransferListenerList.add(l);
        }
    }


    public void setFileDownloadPath(String fileDownloadPath) {
        this.fileDownloadPath = fileDownloadPath;
    }





    /**
     * Retrieves the {@link ObjectInfo} for an object.
     *
     * @param objectHandle the handle of the object
     * @return the MtpObjectInfo
     */
    public ObjectInfo getObjectInfo(int objectHandle) throws PTPException{
        Response response;
        ObjectInfo data = new ObjectInfo(objectHandle, BaselineInitiator.this);

        synchronized (session) {
            response = transact1(Command.GetObjectInfo, data, objectHandle);
            switch (response.getCode()) {
                case Response.OK:
                    data.parse();
                    return data;
                default:
                    throw new PTPException(response.toString());
            }
        }
    }



    ///////////////////////////////////////////////////////////////////
    // rainx added for poll event
    // mandatory for all responders:  generating events
    /**
     * Makes the invoking Thread read and report events reported
     * by the PTP responder, until the Initiator is closed.
     */
    @Override
    public void run() {
            runEventPoll();
    }

    /***
     * Common Event Poll for device
     * sony 走这个，钦伟
     */
    protected void runEventPoll() {
        Log.v("PTP_EVENT", "开始event轮询");
        pollEventSetUp();
        if (epEv != null) {

            while (isSessionActive()) {
                ObjectInfo singal = (ObjectInfo) waitVendorSpecifiedFileReadySignal();
                if(singal!=null){
                    File outputFile = new File(new File(fileDownloadPath), singal.filename);
                    if (outputFile.exists()) {
                        outputFile.delete();
                    }
                    String outputFilePath = outputFile.getPath();
                    try {
                        importFile(singal.handle, outputFilePath);
                    } catch (PTPException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

            }
        }
        Log.v("PTP_EVENT", "结束轮询");
    }

    protected Object waitVendorSpecifiedFileReadySignal() {
        return null;
    }

    // 可以被子类覆盖，进行轮询之前的准备工作
    protected void pollListSetUp() {

    }


    // poll event setup mode
    protected void pollEventSetUp() {

    }

    public void setSyncTriggerMode(int syncTriggerMode) {
        this.syncTriggerMode = syncTriggerMode;
    }


}
