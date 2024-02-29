#![allow(unused)]

#![allow(unused)]

/// same example as alist.rs but this time with a retry_send_msg() if the provider cannot
/// send to the client because it's queue is full.
/// 
/// the main change is that the client 'SimulateNewRequest' and associated action list ('new_request_action')
/// now become regular functional features, i.e. the client provides a 'ExecNewRequest' method that
/// triggers a new (configured) provider request. This message is what we send from the provider snapshot_action
/// instance if the receiver (client) queue is full. This kills two birds with one stone: 
///
///   (a) we make sure that if a ExecSnapshotAction finally succeeds it sends the client the up-to-date data, and 
///   (b) the retry does not have to clone a potentially large message (ExecNewRequest instead of SendSnapshot)

use tokio;
use odin_actor::prelude::*;
use odin_actor::errors::Result;
use colored::Colorize;

//-- to make semantics more clear
type TProviderUpdate = i64;
type TProviderSnapshot = Vec<TProviderUpdate>;
type TClientRequest = String;
type TAddr = String; // client data

/* #region provider ***************************************************************************/

/// provider example, modeling some async changed data store (tracks, sensor readings etc.)
struct Provider<A1,A2> where A1: ActorActionList<TProviderUpdate>, A2: ActorAction2List<TProviderSnapshot,TClientRequest>
{
    data: TProviderSnapshot,
    count: usize,

    update_actions: A1, // actions to be triggered when our data changes
    snapshot_action: A2 // actions to be triggered when a client requests a snapshot
}
impl<A1,A2> Provider<A1,A2> where A1: ActorActionList<TProviderUpdate>, A2: ActorAction2List<TProviderSnapshot,TClientRequest>
{
    fn new(update_actions: A1, snapshot_action: A2)->Self {
        Provider { data: Vec::new(), count: 0, update_actions, snapshot_action }
    }

    fn update(&mut self) {
        self.data.push( self.count as TProviderUpdate);
    }
}

#[derive(Debug)] struct ExecSnapshotAction { client_data: TClientRequest }

define_actor_msg_type! { ProviderMsg = ExecSnapshotAction }

impl_actor! { match msg for Actor<Provider<A1,A2>,ProviderMsg> 
                    where A1: ActorActionList<TProviderUpdate>, A2: ActorAction2List<TProviderSnapshot,TClientRequest> as
    _Start_ => cont! {
        self.hself.start_repeat_timer( 1, secs(1));
        println!("{} started", self.id().white());
    }
    _Timer_ => { // simulate async change of data (e.g. through some external I/O)
        self.count += 1;
        println!("{} update cycle {}", self.id().white(), self.count);
        self.update();

        if self.count < 10 {
            self.update_actions.execute( self.data.last().unwrap()).await;
            ReceiveAction::Continue
        } else {
            println!("{} had enough of it, request termination.", self.id().white()); 
            ReceiveAction::RequestTermination 
        }
    }
    ExecSnapshotAction => cont! { // client requests a full data snapshot
        println!("{} received {msg:?}", self.id().white());
        self.snapshot_action.execute( &self.data, &msg.client_data).await;
    }

}

/* #endregion provider */

/* #region client *********************************************************************************/

/// client example, modeling a web server that manages web socket connections
pub struct WsServer<A> where A: ActorActionList<TAddr> {
    connections: Vec<TAddr>,
    new_request_action: A // action to be triggered when server gets a new (external) connection request
}
impl <A> WsServer<A> where A: ActorActionList<TAddr> {
    pub fn new (new_request_action: A)->Self { WsServer{connections: Vec::new(), new_request_action} }
}

// these message types are too 'WsServer' specific to be forced upon a generic, reusable Provider

#[derive(Debug)] struct PublishUpdate { ws_msg: String }

#[derive(Debug)] struct SendSnapshot { addr: TAddr, ws_msg: String }

#[derive(Debug,Clone)] struct ExecNewRequest { addr: TAddr }

define_actor_msg_type! { WsServerMsg = PublishUpdate | SendSnapshot | ExecNewRequest }

impl_actor! { match msg for Actor<WsServer<A>,WsServerMsg> where A: ActorActionList<TAddr> as
    ExecNewRequest => cont! { // mockup simulating a new external connection event from 'addr'
        println!("{} send connection request for {:?}", self.id().yellow(), msg.addr);
        self.new_request_action.execute( &msg.addr).await;
    }
    PublishUpdate => cont! {
        if self.connections.is_empty() { 
            println!("{} doesn't have connections yet, ignore data update", self.id().yellow())
        } else {
            println!("{} publishing data '{}' to connections {:?}", self.id().yellow(), msg.ws_msg, self.connections)
        }
    }
    SendSnapshot => cont! {
        self.connections.push(msg.addr.clone());
        println!("{} sending snapshot data '{}' to connection '{}'", self.id().yellow(), msg.ws_msg, msg.addr);
    }
}

/* #endregion client */

#[tokio::main]
async fn main ()->Result<()> {
    let mut actor_system = ActorSystem::new("main");
    let pre_provider = PreActorHandle::new( "provider", 8); // we need it to construct the client

    //--- 1: set up the client (WsServer)
    define_actor_action_list! { for actor_handle in NewRequestAction (addr: &TAddr) :
        ProviderMsg => {
            let client_data = addr.clone(); 
            actor_handle.try_send_msg( ExecSnapshotAction{client_data})
        }
    }
    let client = spawn_actor!( actor_system, "client", 
        WsServer::new( NewRequestAction( pre_provider.as_actor_handle(&actor_system)))
    )?;

    //--- 2: set up the provider (data source)
    define_actor_action_list!{ for actor_handle in UpdateAction (v: &TProviderUpdate) :
        WsServerMsg =>  actor_handle.try_send_msg( PublishUpdate{ws_msg: format!("{{\"update\": \"{v}\"}}")})
    }
    define_actor_action2_list! { for actor_handle in SnapshotAction (v: &TProviderSnapshot, addr: &TClientRequest):
        WsServerMsg => {
            ///////////////// this is the main change compared to alist.rs
            match actor_handle.try_send_msg( SendSnapshot{ addr: addr.clone(), ws_msg: format!("{v:?}")}) {
                //Err(OdinActorError::ReceiverFull) => {
                Err(e) => {
                    // this is a critical msg - retry if it failed. While we could directly resend the SendSnapshot()
                    // to the client this would be suboptimal since the provider data has most likely changed
                    // at the time this will succeed, which means all updates in-between original request and success
                    // would be lost. Just sending a control message to the client also means we don't have to clone
                    // a potentially huge message
                    actor_handle.retry_send_msg( 5, millis(300), ExecNewRequest{addr: addr.clone()})
                }
                other => other
            }
            ///////////////// end change
        }
    }
    let provider = spawn_pre_actor!( actor_system, pre_provider, 
        Provider::new( UpdateAction(client.clone()), SnapshotAction(client.clone()))
    )?;


    actor_system.start_all().await?;

    //--- 3: actor system running - now simulate external requests
    sleep( secs(2)).await;
    client.send_msg( ExecNewRequest{addr: "42".to_string()}).await?;
    sleep( secs(3)).await;
    client.send_msg( ExecNewRequest{addr: "43".to_string()}).await?;

    actor_system.process_requests().await?;

    Ok(())
}
