package de.hpi.ddm.actors;

import java.io.Serializable;
import java.util.*;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.Terminated;
import lombok.*;

public class Master extends AbstractLoggingActor {

	/////////////////
	// Actor State //
	/////////////////

	//Master Variables
	public static final String DEFAULT_NAME = "master";
	private int passwordLength = -1; //-1 when no batch has been read
	private char[] charactersInPassword;
	private List<char[]> possibleCombinationsForHintsList;
	HashMap<Integer, Password> ID_PasswordHashMap; //Hashmap with all fields from password file

	//http://tutorials.jenkov.com/java-collections/queue.html
	private Queue<DecryptHintMessage> hintCrackingQueue;
	private  Queue<GoCrackPasswordMessage> passwordCrackingQueue;

	//Actor references
	private final ActorRef reader;
	private final ActorRef collector;

	private final List<ActorRef> workers;
	private List<Boolean> workerOccupied;

	private long startTime;

	////////////////////////
	// Actor Construction //
	////////////////////////
	public static Props props(final ActorRef reader, final ActorRef collector) {
		return Props.create(Master.class, () -> new Master(reader, collector));
	}

	public Master(final ActorRef reader, final ActorRef collector) {
		this.reader = reader;
		this.collector = collector;
		this.workers = new ArrayList<>();
		this.workerOccupied = new ArrayList<>();
		this.possibleCombinationsForHintsList = new ArrayList<char[]>();
		this.passwordLength = -1;
		this.ID_PasswordHashMap = new HashMap<Integer, Password>();

		this.hintCrackingQueue = new LinkedList<DecryptHintMessage>();
		this.passwordCrackingQueue = new LinkedList<GoCrackPasswordMessage>();
	}

	////////////////////
	// Actor Messages //
	////////////////////

	@Data @NoArgsConstructor
	public static class StartMessage implements Serializable {
		private static final long serialVersionUID = -50374816448627600L;
	}

	@Data @NoArgsConstructor @AllArgsConstructor
	public static class BatchMessage implements Serializable {
		private static final long serialVersionUID = 8343040942748609598L;
		private List<String[]> lines;
	}

	@Data @NoArgsConstructor
	public static class RegistrationMessage implements Serializable {
		private static final long serialVersionUID = 3303081601659723997L;
	}

	@Getter @Setter @ToString @AllArgsConstructor @NoArgsConstructor
	public static class DecryptHintMessage implements Serializable {
		private int ID;
		private String hint;
		private char[] hintCharacterCombination; //possible characters in the hint
	}

	@Getter @Setter @ToString @AllArgsConstructor @NoArgsConstructor
	public static class GoCrackPasswordMessage implements Serializable {
		//Maybe just send the object?
		Password password;
	}

	@Getter @Setter @ToString @AllArgsConstructor @NoArgsConstructor
	public static class PasswordCompleteMessage implements Serializable {
		private int ID;
		private String crackedPassword;
		private String encryptedPassword;
	}
	
	/////////////////////
	// Actor Lifecycle //
	/////////////////////

	@Override
	public void preStart() {
		Reaper.watchWithDefaultReaper(this);
	}

	////////////////////
	// Actor Behavior //
	////////////////////

	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(RegistrationMessage.class, this::handle)
				.match(StartMessage.class, this::handle)
				.match(BatchMessage.class, this::handle)
				.match(Worker.DecryptedHint.class, this::handle)
				.match(Worker.PasswordCompleteMessage.class, this::handle)
				.match(Worker.WorkerAvailableMessageToMaster.class, this::handle)
				.match(Terminated.class, this::handle)
				.matchAny(object -> this.log().info("Received unknown message: \"{}\"", object.toString()))
				.build();
	}




	protected void handle(StartMessage message) {
		this.startTime = System.currentTimeMillis();
		this.reader.tell(new Reader.ReadMessage(), this.self()); //7. Master tells reader message:  ReadMessage
	}
	
	protected void handle(BatchMessage message) { //HERE messages arrive in batches sent from Reader
		
		///////////////////////////////////////////////////////////////////////////////////////////////////////
		// The input file is read in batches for two reasons: /////////////////////////////////////////////////
		// 1. If we distribute the batches early, we might not need to hold the entire input data in memory. //
		// 2. If we process the batches early, we can achieve latency hiding. /////////////////////////////////
		// Implement the processing of the data for the concrete assignment. /////////////////////////////////
		///////////////////////////////////////////////////////////////////////////////////////////////////////

		//If nothing is in the message
		if (message.getLines().isEmpty()) {
			this.collector.tell(new Collector.PrintMessage(), this.self());
			this.terminate();
			return;
		}

		if(passwordLength == -1){ //-1 means that it is the first batch
			this.passwordLength = Integer.parseInt(message.getLines().get(0)[3]); //assign password length
			this.charactersInPassword = message.getLines().get(0)[2].toCharArray();//assign possible characters in password
			//Assign combinations for hints (each entry has 10 elements from the 11 characters, so 11 entries in total)
			//System.out.println("Length before creating permutation: " + possibleCombinationsForHintsList.size());
			getCharacterCombinations(this.charactersInPassword, this.passwordLength, possibleCombinationsForHintsList);//make function for combinations
			//System.out.println("Length after creating permutation: " + possibleCombinationsForHintsList.size());
		}

		String[] passwordHints;
		for (String[] messageLine : message.getLines()) {
			//System.out.println(Arrays.toString(messageLine)); //Print message
			//System.out.println(messageLine[4]);
			int ID = Integer.parseInt(messageLine[0]);
			passwordHints = new String[messageLine.length-5];
			for (int i = 5; i < messageLine.length; i++) {
				passwordHints[i-5] = messageLine[i];
			}
			Password password = new Password(ID, messageLine[1], messageLine[4], passwordHints, this.charactersInPassword, this.passwordLength);
			//this.log().info("DEBUG: Password: " + password);
			//System.out.println(password);
			ID_PasswordHashMap.put(password.getID(), password); //adding password to hashmap
			for (int i = 0; i < password.getHintsEncryptedArray().length; i++) {
				for (int j = 0; j < this.possibleCombinationsForHintsList.size(); j++) {
					//add to hintCrackingQueue
					this.hintCrackingQueue.add(new DecryptHintMessage(password.getID(), password.getHintsEncryptedArray()[i], this.possibleCombinationsForHintsList.get(j)));//Add hint cracking task to hintCrackingQueue
				}
			}
		}

		//this.log().info("DEBUG: hintCrackingQueue size: " + hintCrackingQueue.size());
		//this.log().info("DEBUG passwordCrackingQueue size: " + passwordCrackingQueue.size());

		if(this.passwordCrackingQueue.isEmpty()){ //If there are no elements in passwordCrackingQueue
			this.sendDecryptHintMessage(); //9.Send messages from hintCrackingQueue to Workers that are free (workerOccupied)
		}
		else { //If there are elements in passwordCrackingQueue
			sendDecryptPasswordMessage(); //send decrypt password
		}

		//System.out.println("hintCrackingQueue length: " + hintCrackingQueue.size());


		this.collector.tell(new Collector.CollectMessage("Processed batch of size " + message.getLines().size()), this.self());
		this.collector.tell(new Collector.PrintMessage(), this.self());

	}

	protected boolean allWorkersAreFree(){
		for(int i = 0; i < this.workerOccupied.size(); i++) {
			if(this.workerOccupied.get(i) == false){
				return false;
			}
		}
		return true;
	}


	protected void sendDecryptHintMessage(){
		//System.out.println("hintCrackingQueue size: " + hintCrackingQueue.size());
		//System.out.println("passwordCrackingQueue size: " + passwordCrackingQueue.size());
		for (int i = 0; i < workerOccupied.size(); i++) {
			if (this.workerOccupied.get(i)==false){
				try {
					DecryptHintMessage messageToSend = this.hintCrackingQueue.remove(); //.poll para ver si tiene elemento primero
					this.workers.get(i).tell(messageToSend, this.self());
					this.workerOccupied.set(i, true); //Set occupied
				}catch (NoSuchElementException e){};
			}
		}
	}

	protected void sendDecryptPasswordMessage(){
		//System.out.println("hintCrackingQueue size: " + hintCrackingQueue.size());
		//System.out.println("passwordCrackingQueue size: " + passwordCrackingQueue.size());
		for (int i = 0; i < workerOccupied.size(); i++) {
			if (this.workerOccupied.get(i)==false){
				try {
					GoCrackPasswordMessage messageToSend = this.passwordCrackingQueue.remove(); //.poll para ver si tiene elemento primero
					this.workers.get(i).tell(messageToSend, this.self());
					this.workerOccupied.set(i, true); //Set occupied
					this.log().info("PasswordCracking message sent to worker");
				}catch (NoSuchElementException e){};

			}
		}
	}

	private void handle(Worker.WorkerAvailableMessageToMaster workerAvailableMessageToMaster) {
		ActorRef messageSender = this.sender();
		for (int i = 0; i < workers.size(); i++) {
			if(messageSender.equals(workers.get(i))){
				//System.out.println("Worker is available");
				this.workerOccupied.set(i, false); //Set available
				sendDecryptPasswordMessage();
				sendDecryptHintMessage();
				break;
			}
		}
	}

	private void handle(Worker.DecryptedHint message) { //11. Master receives hint decrypted from worker. With this we know worker is free so we can send it more messages
		//save the decrypted hint and send more work
		//first try to send work for password then for hints
		int ID = message.getID();
		String encrypted = message.getEncryptedHint();
		String decrypted = message.getDecryptedHint();
		ActorRef messageSender = this.sender();
		this.log().info("Password hint decrypted from ID: " + ID + " | decrypted hint: " + decrypted);
		//System.out.println("DecryptedHint message received!!");
		for (int i = 0; i < workers.size(); i++) {
			if(messageSender.equals(workers.get(i))){
				if(this.ID_PasswordHashMap.containsKey(ID)){
					this.log().info("Added hint to hashmap with key " + ID);
					this.ID_PasswordHashMap.get(ID).addDecryptedHint(encrypted, decrypted);
					//System.out.println(this.ID_PasswordHashMap.get(ID).toString());
					//System.out.println(this.ID_PasswordHashMap.get(ID).toString());
					this.log().info("Password object: " + this.ID_PasswordHashMap.get(ID).toString());
					//this.log().info("Saved hint for " + this.ID_PasswordHashMap.get(ID).getName() + " with ID: " + this.ID_PasswordHashMap.get(ID).getID() + "\n" + "		Hints Array: " + this.ID_PasswordHashMap.get(ID).getDecryptedPassword().toString());
					break;
				}
				this.workerOccupied.set(i, false); //Set available
			}
		}

		//check if all hints from ID are cracked
		boolean bool = this.ID_PasswordHashMap.get(ID).checkAllDecryptedHintsTrue();
		if(bool == true){
			//send decrypt password message to worker!
			Password password = (Password) ID_PasswordHashMap.get(ID).clone(); //clone the password from hashmap to send to the worker
			this.passwordCrackingQueue.add(new GoCrackPasswordMessage(password));
			this.log().info("Added Password Cracking work for ID" + ID + " with Password object: " + this.ID_PasswordHashMap.get(ID).toString());
			this.log().info("PasswordCrackingQueue size: " + this.passwordCrackingQueue.size());
			sendDecryptPasswordMessage(); //12. send password cracking
			sendDecryptHintMessage();
		}
		else { //If not all hints are cracked
			sendDecryptHintMessage();
		}

		//check if workers are free and there is no more tasks in both queues -> (this means we need to terminate the program)
		if(passwordCrackingQueue.isEmpty() && hintCrackingQueue.isEmpty()){ //Check to see if there are more tasks in queues
			this.reader.tell(new Reader.ReadMessage(), this.self()); //tell reader to send more batches of passwords
		}


	}

	
	protected void terminate() {
		this.reader.tell(PoisonPill.getInstance(), ActorRef.noSender());
		this.collector.tell(PoisonPill.getInstance(), ActorRef.noSender());
		
		for (ActorRef worker : this.workers) {
			this.context().unwatch(worker);
			worker.tell(PoisonPill.getInstance(), ActorRef.noSender());
		}

		//Send all the password objects to the collector inside of the hashmap
		this.collector.tell(new Collector.CollectMessage("Password hashmap: " + ID_PasswordHashMap.toString()), this.self());
		this.collector.tell(new Collector.PrintMessage(), this.self());

		this.self().tell(PoisonPill.getInstance(), ActorRef.noSender());
		
		long executionTime = System.currentTimeMillis() - this.startTime;
		this.log().info("Algorithm finished in {} ms", executionTime);
	}

	protected void handle(RegistrationMessage message) { //5. Master receives message and starts watching it
		this.context().watch(this.sender());
		this.workers.add(this.sender()); //add worker to the worker arraylist from the Master
		this.workerOccupied.add(false);
		//System.out.println("workerOccupied size: " + workerOccupied);
//		this.log().info("Registered {}", this.sender());
	}


	private void handle(Worker.PasswordCompleteMessage message) {
		int id = message.getID();
		ActorRef messageSender = this.sender();
		String decryptedPassword = message.getDecryptedPassword();
		for (int i = 0; i < workers.size(); i++) {
			if(messageSender.equals(workers.get(i))){
				if(this.ID_PasswordHashMap.containsKey(id)){
					if(!decryptedPassword.equals("")){
						ID_PasswordHashMap.get(id).setDecryptedPassword(decryptedPassword);
						this.log().info("Decrypted Password from " + ID_PasswordHashMap.get(id).getName() + " with ID " + ID_PasswordHashMap.get(id).getID() + ": " + decryptedPassword);
						//Send solution to the collector
						this.collector.tell(new Collector.CollectMessage("Decrypted Password from " + ID_PasswordHashMap.get(id).getName() + " with ID " + ID_PasswordHashMap.get(id).getID() + ": " + decryptedPassword), this.self());
						this.collector.tell(new Collector.PrintMessage(), this.self());
						break;
					}
				}
				//System.out.println("Worker is available");
				this.workerOccupied.set(i, false); //Set available
			}

		}

		sendDecryptPasswordMessage();
		sendDecryptHintMessage();

		if(passwordCrackingQueue.isEmpty() && hintCrackingQueue.isEmpty()){ //Check to see if there are more tasks in queues
			this.reader.tell(new Reader.ReadMessage(), this.self()); //tell reader to send more batches of passwords
		}

	}


	
	protected void handle(Terminated message) {
		this.context().unwatch(message.getActor());
		this.workers.remove(message.getActor());
//		this.log().info("Unregistered {}", message.getActor());
	}

	//Character combinations for password hints
	//https://www.geeksforgeeks.org/heaps-algorithm-for-generating-permutations/
	//https://www.geeksforgeeks.org/print-all-combinations-of-given-length/
	private void getCharacterCombinations(char[] possibleCharacters, int passwordLength, List<char[]> dataList) {
		char[] combination = new char[possibleCharacters.length - 1];
		for (int i = 0; i < possibleCharacters.length; i++) {
			int combination_index = 0;
			for (int j = 0; j < possibleCharacters.length; j++) {
				if (j != i) {
					combination[combination_index++] = possibleCharacters[j];
				}
			}
			if (combination.length == passwordLength) {

				dataList.add(combination.clone());
			} else {
				getCharacterCombinations(combination, passwordLength, dataList);
			}
		}
	}


 	//https://projectlombok.org/features/Data
	@Getter @Setter @ToString @NoArgsConstructor
	//needs to be static to serialize with kryo
	//we need it to have a @NoArgsConstructor to serialize
	protected static class Password implements Serializable, Cloneable{
		private int ID;
		private String name;
		private int passwordLength;
		private String encryptedPassword;
		private String decryptedPassword;
		private String[] hintsEncryptedArray;
		private String[] hintsDecryptedArray;
		private char[] possibleCharacters;

		public Password(int ID, String name, String encryptedPassword, String[] hintsEncryptedArray, char[] alphabet, int pwLength){
			this.ID = ID;
			this.name = name;
			this.encryptedPassword = encryptedPassword;
			this.decryptedPassword = "";
			this.hintsEncryptedArray = hintsEncryptedArray.clone();
			this.hintsDecryptedArray = new String[this.hintsEncryptedArray.length];
			Arrays.fill(this.hintsDecryptedArray, "");
			this.possibleCharacters = alphabet;
			this.passwordLength = pwLength;
		}

		public Object clone(){
			try {
				return super.clone();
			} catch (CloneNotSupportedException e){
				return this;
			}
		}

		public void setHintsDecryptedArrayWithIndex(int index, String stringValue){
			hintsDecryptedArray[index] = stringValue;
		}

		public String getHintsDecryptedArrayWithIndex(int index){
			 return hintsDecryptedArray[index];
		}

		public String getHintsEncryptedArrayWithIndex(int index){
			return hintsEncryptedArray[index];
		}

		public int getIndexOFHintsEncryptedArrayElement(String stringElement){
			for (int i = 0; i < hintsEncryptedArray.length; i++) {
				if(stringElement.equals(hintsEncryptedArray[i])){
					return i;
				}
			}
			return -1;
		}

		public void addDecryptedHint(String encrypted, String decrypted){
			int index = getIndexOFHintsEncryptedArrayElement(encrypted);
			setHintsDecryptedArrayWithIndex(index, decrypted);
		}

		//create method to check if all hintsDecryptedArray are different than '' (empty)
		public boolean checkAllDecryptedHintsTrue(){
			for (int i = 0; i < hintsDecryptedArray.length; i++) {
				if(hintsDecryptedArray[i].equals("")){
					return false;
				}
			}
			return true; //if there is no empty hint
		}
	}



}
