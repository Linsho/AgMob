let peer;
const WORKSPACE_BASE_ADDRESS = "160.16.213.209";
const pcConfig = {iceServers: [{urls: "stun:stun.l.google.com:19302"}]};
let video = document.getElementById("agmob-screen-viewer");


function sendWebsocket() {
    var id = document.getElementById("session-id").value;
    var url = `ws://${WORKSPACE_BASE_ADDRESS}:8080/api/session/${id}/navigator`;
    var ws = new WebSocket(url);

    ws.onopen = function() {
      peer = new RTCPeerConnection(pcConfig);
      peer.ontrack = evt => {
        console.log('-- peer.ontrack()');
        console.log(evt.track);
        console.log(evt.streams);
        evt.streams[0].addTrack(evt.track);
        playVideo(video, evt.streams[0]);
      };

      // ICE Candidateを収集したときのイベント
      peer.onicecandidate = evt => {
        if (evt.candidate) {
            console.log(evt.candidate);
        } else {
            console.log('empty ice event');
            const sdp = peer.localDescription;
            const sendObject = {
              "kind": "sdp",
              "payload": JSON.stringify(sdp)
            };
            ws.send(JSON.stringify(sendObject));
        }
      };
      let sendObject = {
        "kind": "request_sdp",
        "payload": "",
      };
      ws.send(JSON.stringify(sendObject));
    };

    ws.onmessage = function(evt) {
        console.log(evt.data);
        const sdp = JSON.parse(evt.data);

        peer.setRemoteDescription(JSON.parse(sdp.payload)).then(() => {
          console.log('setRemoteDescription(answer) success in promise');
          peer.createAnswer().then((answer) => {
            peer.setLocalDescription(answer).then(() => {
              // const sdp = peer.localDescription;
              // const sendObject = {
              //   "kind": "sdp",
              //   "payload": JSON.stringify(sdp)
              // };
              // ws.send(JSON.stringify(sendObject));
            })
          })
        })
    };

    ws.onclose = function() {
        console.log("closed");
    };

    ws.onerror = function(evt) {
        alert("error");
    };
}

// Answer側のSDPをセットする場合
async function setAnswer(sessionDescription) {
  if (! peerConnection) {
    console.error('peerConnection NOT exist!');
    return;
  }
  try{
    await peerConnection.setRemoteDescription(sessionDescription);
    console.log('setRemoteDescription(answer) success in promise');
  } catch(err){
    console.error('setRemoteDescription(answer) ERROR: ', err);
  }
}

// Videoの再生を開始する
async function playVideo(element, stream) {
  element.srcObject = stream;
  try {
      await element.play();
  } catch(err) {
      console.log('error auto play:' + err);
  }
}
