#![allow(unused)]

/// example of how to connect two actors that don't have to know about each others message interfaces.
///
/// The decoupling is achieved through construction-site defined ActionLists (set up in `main()`).
/// ActionList<ProviderData> is triggered by state changes in the provider
/// Action2List<ProviderData,ClientData> is triggered by the client
/// 
/// The underlying model is an async updated data source actor as the provider (e.g. for tracking objects)
/// that implements two abstract interaction points:
///   - update actions when its internal data model changes
///   - snapshot actions triggered by clients, passing in additional client request data
///
/// Note the provider implementation does not need to know the specific ActorHandles/messages of clients
/// and hence also does not need to know about potential data transformation to create such action messages. The
/// only thing it needs to know is when to trigger respective actions and what data to pass into respective
/// `execute(..}` invocations for these actions.
///
/// The client models a web server that receives external connection requests, upon which it
/// needs to send snapshot data that is created through provider callback actions.
/// Once a new connection is established the web server then sends whatever it receives through the provider
/// update actions to all live connections.
/// Note there is nothing in the client that needs to know the concrete provider message interface or its
/// update/snapshot data types. All this information is encapsulated in `ActionList` instances at the
/// system construction site (`main()`)

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
struct Provider<A1,A2> where A1: ActorAction<TProviderUpdate>, A2: ActorAction2<TProviderSnapshot,TClientRequest>
{
    data: TProviderSnapshot,
    count: usize,

    update_actions: A1, // actions to be triggered when our data changes
    snapshot_action: A2 // actions to be triggered when a client requests a snapshot
}
impl<A1,A2> Provider<A1,A2> where A1: ActorAction<TProviderUpdate>, A2: ActorAction2<TProviderSnapshot,TClientRequest>
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
                    where A1: ActorAction<TProviderUpdate>, A2: ActorAction2<TProviderSnapshot,TClientRequest> as
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
pub struct WsServer<A> where A: ActorAction<TAddr> {
    connections: Vec<TAddr>,
    new_request_action: A // action to be triggered when server gets a new (external) connection request
}
impl <A> WsServer<A> where A: ActorAction<TAddr> {
    pub fn new (new_request_action: A)->Self { WsServer{connections: Vec::new(), new_request_action} }
}

// these message types are too 'WsServer' specific to be forced upon a generic, reusable Provider

#[derive(Debug)] struct PublishUpdate { ws_msg: String }

#[derive(Debug)] struct SendSnapshot { addr: TAddr, ws_msg: String }

#[derive(Debug)] struct SimulateNewRequest { addr: TAddr }

define_actor_msg_type! { WsServerMsg = PublishUpdate | SendSnapshot | SimulateNewRequest }

impl_actor! { match msg for Actor<WsServer<A>,WsServerMsg> where A: ActorAction<TAddr> as
    SimulateNewRequest => cont! { // mockup simulating a new external connection event from 'addr'
        // note we don't add msg.addr to connections yet since that could cause sending updates before init snapshots
        println!("{} got new connection request from {:?}", self.id().yellow(), msg.addr);
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
    let pre_provider = PreActorHandle::new( &actor_system, "provider", 8); // we need it to construct the client

    //--- 1: set up the client (WsServer)
    define_actor_action_type! { NewRequestAction = actor_handle <- (addr: &TAddr) for
        ProviderMsg => {
            let client_data = addr.clone(); // transform TAddr into TClientRequest should the two not be the same
            actor_handle.try_send_msg( ExecSnapshotAction{client_data})
        }
    }
    let client = spawn_actor!( actor_system, "client", 
        WsServer::new( NewRequestAction( pre_provider.as_actor_handle()) )
    )?;

    //--- 2: set up the provider (data source)
    define_actor_action_type!{ UpdateAction = actor_handle <- (v: &TProviderUpdate) for
        WsServerMsg =>  actor_handle.try_send_msg( PublishUpdate{ws_msg: format!("{{\"update\": \"{v}\"}}")})
    }
    define_actor_action2_type! { SnapshotAction = actor_handle <- (v: &TProviderSnapshot, addr: &TClientRequest) for
        WsServerMsg => actor_handle.try_send_msg( SendSnapshot{ addr: addr.clone(), ws_msg: format!("{v:?}")} )
    }
    let provider = spawn_pre_actor!( actor_system, pre_provider, 
        Provider::new( UpdateAction(client.clone()), SnapshotAction(client.clone()))
    )?;


    actor_system.start_all().await?;

    //--- 3: actor system running - now simulate external requests
    sleep( secs(2)).await;
    client.send_msg( SimulateNewRequest{addr: "42".to_string()}).await?;
    sleep( secs(3)).await;
    client.send_msg( SimulateNewRequest{addr: "43".to_string()}).await?;

    actor_system.process_requests().await?;

    Ok(())
}
