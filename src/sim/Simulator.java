package sim;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseWheelEvent;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/* TODO:
 * - clean up some code
 * - better documentation
 * - train spawn frequencies built into savefile
 * - find best default parameters
 * - package for release
*/

/* Crazy extensions:
 * - click-to-spawn citizens
 * 		will need to temporarily create additional nodes at points, generate neighbors, then incorporate those into pathfinding mechanisms
 * - prettier citizen spawning (have them generate across the map, then flock to stations?)
 * - ability to click on trains (or citizens?) to see their paths ??
 * 		this could prove very computationally expensive, don't want to check every citizen and train but also don't want to update segments
 * - multithreading ??
 * - zoom to mouse (work out math) & scaling panning to zoom
 * - map rotation ??
 * - background geography ??
 * - simulation statistics (+ graphing ??) ??
 * - time-based pathfinding (not just distance-based, but using train arrival times) ??
 * - trains visually follow ComplexLine paths (this has been moved to another branch!)
 */

public class Simulator {

	public static void main(String [] args) {

		// app initialization
		App app = new Sim(5, Sim.RECOMMENDED_SIM_SPEED_BOUNDS, new Vector3(150, 180, 15), new Vector2(0.9, 1), new Vector2(64, 64), Vector3.white, 1024);

		app.run();

	}

}

class Sim extends App {

	public static final Vector3 RECOMMENDED_SIM_SPEED_BOUNDS = new Vector3(0.2, 0.0, 10.0);
	public static final int DEFAULT_CITIZEN_ALLOCATION = 10000;
	public static final int DEFAULT_TRAIN_ALLOCATION = 8;

	private Line[] lines;
	private Node[] nodes;
	private ArrayList<ArrayList<ArrayList<Drawable>>> segmentedNodes;
	private ComplexLine[] complexLines;
	private int numStops;
	private int nodeSegmentSize;
	private int ridershipTotal;

	private ArrayList<Citizen> citizens;
	
	private boolean paused;
	private boolean drawTrains;
	private boolean drawCitizens;

	private double globalTime;
	private double citizenSpawnCycleTime;
	private double citizenDespawnCycleTime;
	private double timeIncrement;
	private double tempTimeIncrement;
	private double TIME_INCREMENT_INCREMENT;
	private double MIN_TIME_INCREMENT;
	private double MAX_TIME_INCREMENT;

	private double MAP_X_SCALE;
	private double MAP_Y_SCALE;

	private Vector2 textPos;
	private Vector2 text2Pos;
	private Vector2 mouseInitialPos;

	public Sim(double timeIncrement, Vector3 INCREMENT_SETTINGS, Vector3 WORLD_SIZE, Vector2 MAP_X_Y_SCALE, Vector2 windowTopLeft, Vector3 backgroundColor, int windowHeight) {

		assert WORLD_SIZE.x % WORLD_SIZE.z <= 0.0001 && WORLD_SIZE.y % WORLD_SIZE.z <= 0.0001;

		this.timeIncrement = timeIncrement;
		this.tempTimeIncrement = timeIncrement;
		this.TIME_INCREMENT_INCREMENT = INCREMENT_SETTINGS.x;
		this.MIN_TIME_INCREMENT = INCREMENT_SETTINGS.y;
		this.MAX_TIME_INCREMENT = INCREMENT_SETTINGS.z;
		setWindowSizeInWorldUnits(WORLD_SIZE.x, WORLD_SIZE.y);
		this.nodeSegmentSize = (int) WORLD_SIZE.z;
		this.MAP_X_SCALE = MAP_X_Y_SCALE.x * 0.5;
		this.MAP_Y_SCALE = MAP_X_Y_SCALE.y * 0.5;
		setWindowTopLeftCornerInPixels((int) windowTopLeft.x, (int) windowTopLeft.y);
		setWindowCenterInWorldUnits(0.0, 0.0);
		setWindowHeightInPixels(windowHeight);
		setWindowBackgroundColor(backgroundColor);
		this._jFrame.setTitle("CitySim");

	}

	void setup() {

		Logger.log("Started setup");
		
		this.paused = false;
		this.drawTrains = true;
		this.drawCitizens = false;
		
		// mouse wheel zooming
		this.addMouseWheelListener( new MouseAdapter() {
			@Override public void mouseWheelMoved(MouseWheelEvent e) {

				Drawable.adjustZoom(-e.getWheelRotation() * Drawable.ZOOM_CONST);

			}
		});
		
		textPos = new Vector2(-this._windowWidthInWorldUnits / 2, this._windowHeightInWorldUnits / 2 - 2);
		text2Pos = textPos.plus(new Vector2(0, -2));

		// initialize zooming and panning variables
		mouseInitialPos = new Vector2(0, 0);
		Drawable.resetPanZoom();

		// iterate through stations and add stops to appropriate lines
		ArrayList<Node> nodesList = new ArrayList<Node>();
		ArrayList<Double> stationXList = new ArrayList<Double>();
		ArrayList<Double> stationYList = new ArrayList<Double>();

		HashMap<String, Line> lines = new HashMap<String, Line>();

		int c = 0;
		ridershipTotal = 0;
		numStops = 0;
		try (BufferedReader reader = new BufferedReader(new FileReader("src/sim/stations_data.csv")) ) {

			String line;

			while ((line = reader.readLine()) != null) {
				numStops++;
				String[] n = line.split(",");
				String[] stopLines = n[4].split("-");

				for (String str : stopLines) {

					if (!lines.containsKey(str)) {

						lines.put(str, new Line(str));

					}

				}

				int ridership =  Integer.parseInt(n[5]);
				ridershipTotal += ridership;
				Node stop = new Node(n[1], new Vector2(), Vector3.black,ridership);
				nodesList.add(stop);
				stationXList.add(Double.parseDouble(n[2]));
				stationYList.add(Double.parseDouble(n[3]));
				c++;

				// this is rather slow
				for (String str : stopLines) {

					lines.get(str).addStop(stop, 1);

				}

			}

		} catch (IOException e) { assert false; }
		
		nodes = new Node[numStops];
		double[] stationX = new double[numStops];
		double[] stationY = new double[numStops];
		
		for (int i = 0; i < numStops; i++) {
			
			nodes[i] = nodesList.get(i);
			stationX[i] = stationXList.get(i);
			stationY[i] = stationYList.get(i);
			
		}
		
		Logger.log("Read " + nodes.length + " station nodes");

		// convert real-world geometry data to world units
		Vector2 xMinMax = getMinMax(stationX);
		Vector2 yMinMax = getMinMax(stationY);
		normalize(stationX, -this._windowWidthInWorldUnits * MAP_X_SCALE, this._windowWidthInWorldUnits * MAP_X_SCALE, xMinMax.x, xMinMax.y);
		normalize(stationY, -this._windowHeightInWorldUnits * MAP_Y_SCALE, this._windowHeightInWorldUnits * MAP_Y_SCALE, yMinMax.x, yMinMax.y);

		for (int i = 0; i < stationX.length; i++) {

			nodes[i].setPos(stationX[i], stationY[i]);

		}

		// used for easiest n-nearest detection throughout
		segmentedNodes = new ArrayList<ArrayList<ArrayList<Drawable>>>();

		for (int i = 0; i < (int) this._windowWidthInWorldUnits / nodeSegmentSize + 3; i++) {

			segmentedNodes.add(new ArrayList<ArrayList<Drawable>>());
			for (int j = 0; j < (int) this._windowHeightInWorldUnits / nodeSegmentSize + 3; j++) {

				segmentedNodes.get(i).add(new ArrayList<Drawable>());

			}

		}

		for (Node n : nodes) { 

			int xIndex = ((int) (this._windowWidthInWorldUnits/2 + n.getPos().x)/nodeSegmentSize) + 1;
			int yIndex = ((int) (this._windowHeightInWorldUnits/2 + n.getPos().y)/nodeSegmentSize) + 1;

			segmentedNodes.get(xIndex).get(yIndex).add(n);
			n.setSegmentIndex(xIndex, yIndex);

		}

		for (Node n : nodes) {

			for (int i = -1; i <= 1; i++) {

				for (int j = -1; j <= 1; j++) {

					for (Drawable n2 : segmentedNodes.get(n.getXSegmentIndex()+i).get(n.getYSegmentIndex()+j)) {

						Node n2node = (Node) n2;
						double dist = Vector2.distanceBetween(n.getPos(), n2.getPos());
						if (dist <= Node.DEFAULT_TRANSFER_MAX_DIST) {

							Node.addNeighborPair(n, n2node, dist * Node.DEFAULT_TRANSFER_WEIGHT + Node.DEFAULT_CONST_TRANSFER_PENALTY, Line.WALKING_LINE);

						}

					}

				}

			}

		}
		
		Logger.log("Segmented nodes into " + segmentedNodes.size() + " by " + segmentedNodes.get(0).size() + " grid and processd position data.");

		// load in configurations for proper stop orders for lines
		HashMap<String, String> lineConfigs = new HashMap<String, String>();
		try (BufferedReader reader = new BufferedReader(new FileReader("src/sim/lines_stations.csv")) ) {

			String line;
			while ((line = reader.readLine()) != null) {

				if (line.indexOf(",") == -1) { continue; }
				String lineID = line.substring(0, line.indexOf(","));
				lines.get(lineID).setColor(new Vector3Mod(line.substring(line.indexOf(",") + 1, line.indexOf(",", line.indexOf(",") + 1))));
				lineConfigs.put(line.substring(0, line.indexOf(",")), line.substring(line.indexOf(",", line.indexOf(",") + 1) + 1));

			}

		} catch (IOException e) { assert false; }

		// apply line configurations
		c = 0;
		for (Line l : lines.values()) {

			try {

				l.rearrangeStops(lineConfigs.get(l.getID()).split(","));
				l.overrideStopColors();
				
				Train[] trains = new Train[l.getLength() / Line.DEFAULT_TRAIN_SPAWN_SPACING + 1];
				for (int i = 0; i < trains.length; i++) {

					trains[i] = new Train(c+"", this, (i * Line.DEFAULT_TRAIN_SPAWN_SPACING) % l.getLength(), l, l.getColor(), Train.DEFAULT_TRAIN_SPEED);
					c++;

				}
				
				l.setTrains(trains);

			} catch (Exception e) {

				Logger.log("Caught invalid line " + l.getID());
				assert false;
				
			}

		}

		Logger.log("Loaded and applied line configurations for lines " + lines.keySet());

		// add lines to simulation array
		c = 0;
		this.lines = new Line[lines.keySet().size()];

		for (Line l : lines.values()) {

			this.lines[c++] = l;

		}

		// generate complex lines for drawing
		ArrayList<ComplexLine> complexLinesBuilder = new ArrayList<ComplexLine>();
		try(BufferedReader reader = new BufferedReader(new FileReader("src/sim/lines_geom_data.csv")) ) {

			String line;

			while ((line = reader.readLine()) != null) {

				String[] lineData = line.split(",");
				Vector3 lineColor = Vector3.black;
				if (lines.containsKey(lineData[4])) {

					lineColor = lines.get(lineData[4]).getColor();

				}
				String[] lineX = lineData[6].split(" ");
				String[] lineY = lineData[7].split(" ");
				double[] lineXD = new double[lineX.length];
				double[] lineYD = new double[lineY.length];
				Node[] lineNodes = new Node[lineX.length];

				for (int i = 0; i < lineX.length; i++) {

					lineXD[i] = Double.parseDouble(lineX[i]);
					lineYD[i] = Double.parseDouble(lineY[i]);

				}

				normalize(lineXD, -this._windowWidthInWorldUnits * MAP_X_SCALE, this._windowWidthInWorldUnits * MAP_X_SCALE, xMinMax.x, xMinMax.y);
				normalize(lineYD, -this._windowHeightInWorldUnits * MAP_Y_SCALE, this._windowHeightInWorldUnits * MAP_Y_SCALE, yMinMax.x, yMinMax.y);

				for (int i = 0; i < lineXD.length; i++) {

					lineNodes[i] = new Node(new Vector2(lineXD[i], lineYD[i]), lineColor, 0);

				}

				complexLinesBuilder.add(new ComplexLine(lineData[1], lineColor, lineNodes, Double.parseDouble(lineData[5])));

			}

		} catch (IOException e) { assert false; }

		complexLines = new ComplexLine[complexLinesBuilder.size()];
		for (int i = 0; i < complexLines.length; i++) {

			complexLines[i] = complexLinesBuilder.get(i);

		}
		
		for (ComplexLine n : complexLines) { 

			int xIndex = ((int) (this._windowWidthInWorldUnits/2 + n.getPos().x)/nodeSegmentSize) + 1;
			int yIndex = ((int) (this._windowHeightInWorldUnits/2 + n.getPos().y)/nodeSegmentSize) + 1;

			segmentedNodes.get(xIndex).get(yIndex).add(n);
			n.setSegmentIndex(xIndex, yIndex);

		}
		
		Logger.log("Loaded and generated " + complexLines.length + " ComplexLine objects and segmented into grid");

		citizens = new ArrayList<Citizen>(Sim.DEFAULT_CITIZEN_ALLOCATION);
		this.citizenSpawnCycleTime = 0;
		this.citizenDespawnCycleTime = 0;
		
		for (int i = 0; i < Citizen.INITIAL_SPAWN_AMOUNT; i++) {
			
			citizens.add(new Citizen(this, Node.findPath(sample(nodes, ridershipTotal), sample(nodes, ridershipTotal))));
			
		}
		
		Logger.log("Populated " + Citizen.INITIAL_SPAWN_AMOUNT + " citizens");
		
		Logger.log("Starting simulation!");
		Logger.disable();
				
	}

	void loop() {

		this.globalTime += timeIncrement;
		this.citizenSpawnCycleTime += timeIncrement;
		this.citizenDespawnCycleTime += timeIncrement;

		// panning and zooming with mouse and keyboard
		if (mousePressed) {

			mouseInitialPos = new Vector2(mousePosition).minus(Drawable.getMousePan());

		} if (mouseHeld) {

			Drawable.setMousePan(mousePosition.minus(mouseInitialPos));

		}

		if (keyHeld('A')) {

			Drawable.adjustPan(-Drawable.PAN_CONST, 0);

		} if (keyHeld('D')) {

			Drawable.adjustPan(Drawable.PAN_CONST, 0);

		} if (keyHeld('W')) {

			Drawable.adjustPan(0, Drawable.PAN_CONST);

		} if (keyHeld('S')) {

			Drawable.adjustPan(0, -Drawable.PAN_CONST);

		}

		if (keyHeld('=')) {

			Drawable.adjustZoom(Drawable.ZOOM_CONST);

		} if (keyHeld('-')) {

			Drawable.adjustZoom(-Drawable.ZOOM_CONST);

		}

		if (keyPressed('C')) {

			Drawable.resetPanZoom();

		}

		// simulation speed adjustments
		if (keyPressed('1')) {

			this.timeIncrement = Drawable.constrict(timeIncrement-TIME_INCREMENT_INCREMENT, MIN_TIME_INCREMENT, MAX_TIME_INCREMENT);

		} if (keyPressed('2')) {

			this.timeIncrement = Drawable.constrict(timeIncrement+TIME_INCREMENT_INCREMENT, MIN_TIME_INCREMENT, MAX_TIME_INCREMENT);

		} if (keyPressed('P')) {
			
			if (paused) {
				
				this.timeIncrement = this.tempTimeIncrement;
				
			} else {
				
				this.tempTimeIncrement = timeIncrement;
				this.timeIncrement = 0;
				
			}
			
			paused = !paused;
			
		}
		
		// drawing adjustments
		if (keyPressed('T')) {
			
			drawTrains = !drawTrains;
			
		}
		
		if (keyPressed('Y')) {
			
			drawCitizens = !drawCitizens;
			
		}
		
		// despawn citizens
		// amount of citizens seems proportional to simulation speed in an unintended way, but it's probably fine for now
		int c1 = 0; int c2 = 0;
		if (this.citizenDespawnCycleTime >= Citizen.DESPAWN_INTERVAL) {
			
			this.citizenDespawnCycleTime = 0;
			for (int i = 0; i < citizens.size(); i++) {
				
				if (citizens.get(i).getGlobalTime() >= Citizen.MAX_TIME_ALIVE) {
					
					c1++;
					citizens.get(i).getCurrentNode().removeCitizen(); // this may cause excessive removals
					citizens.remove(i);
					continue;
					
				}
				
				if (citizens.get(i).getStatus().equals(TransitStatus.DESPAWN)) {
					
					c2++;
					citizens.remove(i);
					
				}
				
			}
			
			Logger.log("Despawned " + (c1+c2) + " citizens, " + c1 + " garbage collected and " + c2 + " natural");
			
		}
		
		// spawn citizens
		if (this.citizenSpawnCycleTime >= Citizen.SPAWN_INTERVAL) {
			
			this.citizenSpawnCycleTime = 0;
			int max = Citizen.SPAWN_RANGE;
			if (Citizen.SPAWN_RANDRANGE) { max *= Math.random(); }
			for (int i = 0; i < max; i++) {
				
				citizens.add(new Citizen(this, Node.findPath(sample(nodes, ridershipTotal), sample(nodes, ridershipTotal))));
				
			}
			Logger.log("Spawned " + max + " citizens");
			
		}
		
		// draw game objects
		for (ComplexLine cl : complexLines) {

			Drawable.drawComplexLine(this, cl);

		}

		for (Node n : nodes) {

			Drawable.drawCircle(this, n);

		}
		
		for (Line l : lines) {

			for (Train t : l.getTrains()) {

				t.updatePosAlongLine();
				if (drawTrains) {
					
					Drawable.drawCircle(this, t);
					Drawable.drawString(this, t, t.getLine().getID(), Vector3.black, Train.FONT_SIZE_CONST, Train.FONT_CENTERED);
					
				}

			}

		}
		
		for (Citizen c : citizens) {

			c.followPath();
			// checking for walking speeds up draw time significantly, but may hinder debug efforts
			if (drawCitizens && c.getStatus().equals(TransitStatus.WALKING)) {
				
				Drawable.drawCircle(this, c);
				
			}

		}
		
		drawString("Current simulation speed: " + String.format("%.3f", this.timeIncrement), this.textPos, Vector3.black, 12, false);
		drawString("Active citizen agents: " + citizens.size(), this.text2Pos, Vector3.black, 12, false);

	}
	
	public static Vector2 getMinMax(double[] arr) {

		if (arr == null || arr.length == 0) { return null; }

		double minInArr = arr[0];
		double maxInArr = arr[0];
		for (double i : arr) {

			if (i < minInArr) { minInArr = i; }
			if (i > maxInArr) { maxInArr = i; }

		}

		return new Vector2(minInArr, maxInArr);

	}

	public static boolean normalize(double[] arr, double min, double max, double minInArr, double maxInArr) {

		double maxMinDiff = max - min;
		double maxMinInArrDiff = maxInArr - minInArr;

		if (maxMinInArrDiff + min == 0) { return false; }

		for (int i = 0; i < arr.length; i++) {

			arr[i] = maxMinDiff * (arr[i] - minInArr) / (maxMinInArrDiff) + min;

		}

		return true;

	}
	
	public static Node sample(Node[] nodes, int sum) {
		
		int rand = (int)(Math.random() * sum);
		int randCount = 0;
		for (Node n : nodes) {
			
			if (randCount >= rand) {
				
				return n;
				
			}
			randCount += n.getRidership();
			
		}
		
		return nodes[nodes.length-1];
		
	}

	public double getTimeIncrement() { return this.timeIncrement; }
	public double getGlobalTime() { return this.globalTime; }
	public String toString() { return "City simulation running for " + globalTime + " ticks."; }
	
	static class Logger {
		
		private static long time = System.currentTimeMillis();
		private static boolean enabled = true;
		
		public static void log(String str) {
			
			if (!enabled) { return; }
			System.out.println(String.format("%.3f", (System.currentTimeMillis() - time) / 1000.0) + "ms: " + str);
			
		}
		
		public static void enable() { enabled = true; }
		public static void disable() { enabled = false; }
		public static boolean isEnabled() { return enabled; }
		
	}

}

class Drawable {

	final static double ZOOM_CONST = 0.05;
	final static double ZOOM_MAX = 4;
	final static double ZOOM_MIN = 0.1;
	final static double PAN_CONST = 0.25;
	final static double PAN_X_MINMAX = 50;
	final static double PAN_Y_MINMAX = 50;

	private static double zoom;
	private static Vector2 pan;
	private static Vector2 mousePan;

	private String id;
	private Vector2 pos;
	private Vector3 color;
	private double size;
	
	private int xSegmentIndex;
	private int ySegmentIndex;

	public Drawable(Vector2 pos, Vector3 color, double size) {

		this.id = "";
		this.pos = pos;
		this.color = color;
		this.size = size;

	}

	public Drawable(String id, Vector2 pos, Vector3 color, double size) {

		this.id = id;
		this.pos = pos;
		this.color = color;
		this.size = size;

	}
	
	public static double constrict(double d, double min, double max) {

		return Math.min(Math.max(d, min), max);

	}

	public static void adjustPan(double x, double y) {

		pan = pan.minus(new Vector2(x, y));
		constrictPan(pan);

	}

	public static void constrictPan(Vector2 pan) {

		pan.x = constrict(pan.x, -PAN_X_MINMAX, PAN_X_MINMAX);
		pan.y = constrict(pan.y, -PAN_Y_MINMAX, PAN_Y_MINMAX);

	}

	public static void adjustZoom(double z) {

		zoom = Math.min(Math.max(zoom+z, ZOOM_MIN), ZOOM_MAX);

	}

	public static void drawCircle(App a, Drawable d) {

		drawCircle(a, d, d.getColor());

	}
	
	public static void drawCircle(App a, Drawable d, Vector3 col) {

		a.drawCircle(d.getPos().plus(pan).plus(mousePan).times(zoom), d.getSize() * zoom, col);

	}

	public static void drawLine(App a, Drawable d1, Drawable d2) {

		drawLine(a, d1, d2, d2.getColor());

	}

	public static void drawLine(App a, Drawable d1, Drawable d2, Vector3 col) {

		a.drawLine(d1.getPos().plus(pan).plus(mousePan).times(zoom), d2.getPos().plus(pan).plus(mousePan).times(zoom), col);

	}

	public static void drawComplexLine(App a, ComplexLine cl) {

		drawComplexLine(a, cl, cl.getColor());

	}

	public static void drawComplexLine(App a, ComplexLine cl, Vector3 col) {

		for (int i = 1; i < cl.getNodesSize(); i++) {

			drawLine(a, cl.getNode(i-1), cl.getNode(i), col);

		}

	}

	public static void drawPath(App a, ArrayList<Node.PathWrapper> path) {

		drawCircle(a, path.get(0).getNode());
		for (int i = 1; i < path.size(); i++) {

			drawCircle(a, path.get(i).getNode(), Vector3.red);
			drawLine(a, path.get(i-1).getNode(), path.get(i).getNode(), Vector3.red);

		}

	}

	public static void drawString(App a, Drawable d, String str, int size, boolean centered) {

		drawString(a, d, str, d.getColor(), size, centered);

	}

	public static void drawString(App a, Drawable d, String str, Vector3 col, int size, boolean centered) {

		a.drawString(str, d.pos.plus(pan).plus(mousePan).times(zoom), col, (int) Math.ceil(size * zoom), centered);

	}

	public static void resetPanZoom() { pan = new Vector2(); mousePan = new Vector2(); zoom = 1; }
	public static void setPan(Vector2 p) { constrictPan(p); pan = p; }
	public static void setMousePan(Vector2 mp) { constrictPan(mp); mousePan = mp; }
	public static void setZoom(double z) { zoom = z; }
	public static Vector2 getPan() { return pan; }
	public static Vector2 getMousePan() { return mousePan; }
	public static double getZoom() { return zoom; }

	public void setID(String id) { this.id = id; }
	public void setPos(Vector2 pos) { this.pos = pos; }
	public void setPos(double x, double y) { this.pos.x = x; this.pos.y = y; }
	public void setX(double x) { this.pos.x = x; }
	public void setY(double y) { this.pos.y = y; }
	public void setSegmentIndex(int x, int y) { this.xSegmentIndex = x; this.ySegmentIndex = y; }
	public void setColor(Vector3 col) { this.color = col; }
	public void setSize(double size) { this.size = size; }
	public String getID() { return this.id; }
	public Vector2 getPos() { return this.pos; }
	public double getX() { return this.pos.x; }
	public double getY() { return this.pos.y; }
	public int getXSegmentIndex() { return this.xSegmentIndex; }
	public int getYSegmentIndex() { return this.ySegmentIndex; }
	public Vector3 getColor() { return this.color; }
	public double getSize() { return this.size; }
	public String toString() { return "Drawable id=" + this.id + " pos=" + this.pos; }

}

class CitizenContainer extends Drawable {

	public static final double MAX_SIZE = Node.DEFAULT_NODE_SIZE * 5;
	
	private int numCitizens;
	
	public CitizenContainer(String id, Vector2 pos, Vector3 color, double size) {
	
		super(id, pos, color, size);
		this.numCitizens = 0;
	
	}
	
	public void addCitizen() { this.numCitizens++; }
	public void removeCitizen() { this.numCitizens = Math.max(0, --this.numCitizens); }
	public int getCitizens() { return this.numCitizens; }
	public double getSize() { return Math.min(MAX_SIZE, this.numCitizens * Citizen.DEFAULT_CONTAINER_CITIZEN_SIZE + super.getSize()); }
	
}

class Node extends CitizenContainer {

	public static final double DEFAULT_NODE_SIZE = 0.5;
	public static final double DEFAULT_TRANSFER_WEIGHT = 20;
	public static final double DEFAULT_TRANSFER_MAX_DIST = 3;
	public static final double DEFAULT_CONST_STOP_PENALTY = Train.DEFAULT_STOP_DURATION * 2;
	public static final double DEFAULT_CONST_TRANSFER_PENALTY = DEFAULT_CONST_STOP_PENALTY * 2;

	private HashMap<PathWrapper, Double> neighbors;
	private HashMap<String, Train> currentTrains;

	private double ridership;
	private double score;

	public Node(Vector2 pos, Vector3 color, int ridership) {

		this("", pos, color, ridership);

	}

	public Node(String id, Vector2 pos, Vector3 color, int ridership) {

		super(id, pos, color, DEFAULT_NODE_SIZE);
		clear();
		this.score = 0; this.ridership = ridership;

	}

	public void addNeighbor(Node n, double d, Line l) {

		if (n == this) { return; }
		neighbors.put(new PathWrapper(n, l), d);

	}

	public static void addNeighborPair(Node a, Node b, double dist, Line l) {

		a.addNeighbor(b, dist, l);
		b.addNeighbor(a, dist, l);

	}

	// pathfinding
	public double getScore() { return this.score; }
	public void setScore(double score) { this.score = score; }

	// line-based path generation may still not be 100% working as intended
	public static ArrayList<PathWrapper> findPath(Node start, Node end) {

		PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingDouble(Node::getScore));
		Set<Node> visited = new HashSet<>();
		HashMap<Node, PathWrapper> from = new HashMap<Node, PathWrapper>();
		HashMap<Node, Double> score = new HashMap<Node, Double>();

		score.put(start, 0.0);
		start.setScore(score.get(start) + scoreHeuristic(start, end));
		queue.add(start);

		while (!queue.isEmpty()) {

			Node current = queue.poll();

			// path found
			if (current.equals(end)) {

				ArrayList<PathWrapper> path = reconstructPath(from, end);
				if (path.size() > 0) { path.add(new PathWrapper(end, path.get(path.size()-1).getLine())); }
				return path;

			}

			visited.add(current);

			for (PathWrapper pathWrapper : current.getNeighbors().keySet()) {

				Node neighbor = pathWrapper.getNode();

				if (visited.contains(neighbor)) { continue; }

				Line line = pathWrapper.getLine();
				double aggregateScore = score.get(current) + current.getNeighbors().get(pathWrapper);

				if (from.get(neighbor) != null && !from.get(neighbor).getLine().equals(line)) {

					aggregateScore += Node.DEFAULT_CONST_TRANSFER_PENALTY;

				}

				if (!queue.contains(neighbor) || aggregateScore < score.get(neighbor)) {

					from.put(neighbor, new PathWrapper(current, line));
					score.put(neighbor, aggregateScore);
					neighbor.setScore(score.get(neighbor) + scoreHeuristic(neighbor, end));

					if (!queue.contains(neighbor)) {

						queue.add(neighbor);

					}

				}

			}
		}

		return null; // no path

	}

	private static double scoreHeuristic(Node a, Node b) {

		return Vector2.distanceBetween(a.getPos(), b.getPos());

	}

	private static ArrayList<PathWrapper> reconstructPath(HashMap<Node, PathWrapper> from, Node current) {

		ArrayList<PathWrapper> path = new ArrayList<>();

		while (from.containsKey(current)) {

			PathWrapper currentPW = from.get(current);
			current = currentPW.getNode();
			path.add(new PathWrapper(current, currentPW.getLine()));

		}

		for (int i = 0; i < path.size()/2; i++) {

			int j = path.size()-1-i;
			PathWrapper temp = path.get(i);
			path.set(i, path.get(j));
			path.set(j, temp);

		}

		return path;

	}

	public void addTrain(Train train) { if (!this.currentTrains.containsKey(train.getID())) { this.currentTrains.put(train.getID(), train); } }
	public void removeTrain(Train train) { this.currentTrains.remove(train.getID()); }
	public void clear() { clearNeighbors(); clearTrains(); }
	public void clearNeighbors() { this.neighbors = new HashMap<PathWrapper, Double>(); }
	public void clearTrains() { this.currentTrains = new HashMap<String, Train>(Sim.DEFAULT_TRAIN_ALLOCATION); }
	public void setRidership(double d) { this.ridership = d; }
	public HashMap<PathWrapper, Double> getNeighbors() { return this.neighbors; }
	public HashMap<String, Train> getCurrentTrains() { return this.currentTrains; }
	public double getRidership() { return this.ridership; }
	public String toString() { return "Node id=" + this.getID() + " pos=" + this.getPos(); }

	static class PathWrapper {

		private Node node;
		private Line line;

		public PathWrapper(Node node, Line line) {

			this.node = node;
			this.line = line;

		}

		public Node getNode() { return this.node; }
		public Line getLine() { return this.line; }
		public String toString() { return "PathWrapper line=" + line.getID() + " node=" + node; }

	}

}

class ComplexLine extends Drawable {

	private Node[] nodes;
	private Node head;
	private Node tail;
	private double length;

	public ComplexLine(String id, Vector3 col, Node[] nodes, double length) {

		super(id, nodes[0].getPos(), col, -1);
		this.head = nodes[0];
		this.tail = nodes[nodes.length-1];
		this.nodes = nodes;
		this.length = length;

	}

	public void setNodes(Node[] nodes) { this.nodes = nodes; }
	public void setLength(double length) { this.length = length; }
	public Node[] getNodes() { return this.nodes; }
	public Node getHead() { return this.head; }
	public Node getTail() { return this.tail; }
	public Node getNode(int i) { return this.nodes[i]; }
	public int getNodesSize() { return nodes.length; }
	public double getLength() { return length; }
	public String toString() { return "ComplexLine id=" + this.getID() + " nodes=" + Arrays.deepToString(nodes); }

}

enum TransitStatus {

	WALKING, LINE_TRANSFER, WAITING_AT_STATION, ON_TRAIN, SPAWN, DESPAWN

}

class Citizen extends Drawable {

	public static final Vector3 DEFAULT_CITIZEN_COLOR = Vector3.black;
	public static final double DEFAULT_CITIZEN_SIZE = 0.25;
	public static final double DEFAULT_CONTAINER_CITIZEN_SIZE = 0.025;
	public static final double DEFAULT_UNLOAD_TIME = Train.DEFAULT_STOP_DURATION / 3;
	public static final double DEFAULT_CITIZEN_SPEED = 0.2;
	public static final double DESPAWN_INTERVAL = 4;
	public static final double MAX_TIME_ALIVE = 2000;
	public static final int INITIAL_SPAWN_AMOUNT = 1000;
	public static final double SPAWN_INTERVAL = 4;
	public static final int SPAWN_RANGE = 64;
	public static final boolean SPAWN_RANDRANGE = false;

	private Sim sim;
	private TransitStatus status;
	private Node currentNode;
	private Node nextNode;
	private Train currentTrain;
	private Line currentLine;
	private Line nextLine;
	private Node.PathWrapper[] path;
	private int pathIndex;
	private boolean justBoarded;

	private double globalTime;
	private double actionTime;
	private double walkTime;
	private double walkDist;
	private Vector2 initialWalkPos;
	private double speed;

	public Citizen(Sim sim) {

		super(new Vector2(), Citizen.DEFAULT_CITIZEN_COLOR, Citizen.DEFAULT_CITIZEN_SIZE);
		this.sim = sim;
		clearVariables();
		
	}

	public Citizen(Sim sim, ArrayList<Node.PathWrapper> path) {

		super(new Vector2(), Citizen.DEFAULT_CITIZEN_COLOR, Citizen.DEFAULT_CITIZEN_SIZE);
		this.sim = sim;
		this.path = new Node.PathWrapper[path.size()];
		for (int i = 0; i < this.path.length; i++) {

			this.path[i] = path.get(i);

		}
		clearVariables();

	}

	// just so you know, this function is a pretty horrible mess
	// may not be 100% conservative (e.g. extra citizens may be getting incorrectly added/removed from visuals)
	public void followPath() {
				
		if (status == TransitStatus.DESPAWN) { return; }
		
		if (actionTime == 0 && pathIndex == 0) {

			setPos(path[0].getNode().getPos());

		}

		double modSpeed = speed * sim.getTimeIncrement();
		globalTime += sim.getTimeIncrement();
		actionTime += sim.getTimeIncrement();

		if (pathIndex == path.length) {

			this.getCurrentNode().removeCitizen();
			despawn();
			return;

		}

		nextNode = path[pathIndex].getNode();
		nextLine = path[pathIndex].getLine();

		switch (this.status) {

		case WALKING:
			if (walkTime == 0) {
				
				walkDist = Vector2.distanceBetween(this.getPos(), nextNode.getPos());
				initialWalkPos = this.getPos();
				
			}
			if (walkTime >= walkDist) {
				
				// at station, ready to proceed to next path node
				walkTime = 0;
				moveAlongPath();
				if (currentLine == Line.WALKING_LINE) {

					status = TransitStatus.WALKING;

				} else {

					status = TransitStatus.WAITING_AT_STATION;
					this.currentNode.addCitizen();
					
				}

			} else {

				// walk to station
				setPos(Vector2.lerp(walkTime/walkDist, initialWalkPos, nextNode.getPos()));
				walkTime += modSpeed;
				
			}

			break;
		case LINE_TRANSFER:
			if (actionTime >= DEFAULT_UNLOAD_TIME) {

				// ready to wait for train
				actionTime = 0;
				status = TransitStatus.WAITING_AT_STATION;
				
			}
			break;
		case WAITING_AT_STATION:
			for (Train t : this.currentNode.getCurrentTrains().values()) {

				// path-taking is greedy (e.g. takes any available train to next stop)
				if (t.getRealNextStop().equals(nextNode) && t.getCitizens() <= Train.DEFAULT_TRAIN_CAPACITY) {

					// ready to board train
					this.currentNode.removeCitizen();
					currentTrain = t;
					this.currentTrain.addCitizen();
					moveAlongPath();
					status = TransitStatus.ON_TRAIN;
					justBoarded = true;
					break;

				}

			}
			break;
		case ON_TRAIN:
			if (justBoarded && currentTrain.getStatus().equals(TransitStatus.ON_TRAIN)) { justBoarded = false; }
			if (!justBoarded && currentTrain.getStatus().equals(TransitStatus.WAITING_AT_STATION) && currentTrain.getStop().equals(currentNode)) {

				// how on earth is this not executing many times??
				moveAlongPath();
				
				if (!currentLine.equals(currentTrain.getLine())) {

					// at station and transfer required, ready to proceed to next path node
					this.currentTrain.removeCitizen();
					if (currentLine.equals(Line.WALKING_LINE)) {
						
						status = TransitStatus.WALKING;
						
					} else {
						
						status = TransitStatus.LINE_TRANSFER;
						currentNode.addCitizen();
						
					}
					
					currentTrain = null;
					actionTime = 0;
						
				}

			} else {

				// move with train
				setPos(currentTrain.getPos());

			}
			break;
		case SPAWN:
			if (nextLine.equals(Line.WALKING_LINE)) {

				status = TransitStatus.WALKING;

			} else {

				status = TransitStatus.WAITING_AT_STATION;
				moveAlongPath();
				this.currentNode.addCitizen();
				
			}
			break;
		default:
			break;
			
		}

	}
	
	private void despawn() {
		
		// assumes that node removals have already been handled correctly, this could result in excess visual accumulation across nodes
		if (this.currentTrain != null) {
			
			this.currentTrain.removeCitizen();
			
		}
		
		this.status = TransitStatus.DESPAWN;
		
	}

	private void moveAlongPath() {

		actionTime = 0;
		currentNode = nextNode;
		currentLine = nextLine;
		pathIndex++;
		if (pathIndex == path.length) {
			
			despawn();
			return;
			
		}
		nextNode = path[pathIndex].getNode();
		nextLine = path[pathIndex].getLine();

	}

	private void clearVariables() {

		if (this.path == null || this.path.length <= 1) {
			
			this.status = TransitStatus.DESPAWN;
			return;
			
		}
		
		setPos(path[0].getNode().getPos());
		this.status = TransitStatus.SPAWN;
		this.pathIndex = 0;
		this.globalTime = 0;
		this.actionTime = 0;
		this.walkTime = 0;
		this.speed = Citizen.DEFAULT_CITIZEN_SPEED;
		justBoarded = false;

	}

	public void setStatus(TransitStatus status) { this.status = status; }
	public void setNode(Node node) { this.currentNode = node; }
	public void setTrain(Train train) { this.currentTrain = train; }
	public void setLine(Line line) { this.currentLine = line; }
	public void setPath(Node.PathWrapper[] path) { assert path != null & path.length >= 1; this.path = path; this.nextNode = path[0].getNode(); this.nextLine = path[0].getLine(); }
	public double getGlobalTime() { return this.globalTime; }
	public double getActionTime() { return this.actionTime; }
	public TransitStatus getStatus() { return this.status; }
	public Node getCurrentNode() { return this.currentNode; }
	public Train getCurrentTrain() { return this.currentTrain; }
	public Line getCurrentLine() { return this.currentLine; }
	public Node.PathWrapper[] getPath() { return this.path; }
	public Node.PathWrapper getCurrentPathStep() { return this.path[this.pathIndex]; }
	public int getPathIndex() { return this.pathIndex; }
	public String toString() { return "Citizen id=" + getID() + " pos=" + getPos() + " pathStep=" + getCurrentPathStep(); }

}

class Train extends CitizenContainer {

	public static final int DEFAULT_TRAIN_CAPACITY = 150;
	public static final double DEFAULT_TRAIN_SIZE = 1.0;
	public static final double DEFAULT_STOP_DURATION = 8;
	public static final double DEFAULT_TRAIN_SPEED = 0.3;
	public static final int FONT_SIZE_CONST = 11;
	public static final boolean FONT_CENTERED = true;

	private TransitStatus status;
	private Sim sim;
	private Line line;
	private int stop;
	private int nextStop;
	private double globalTime;
	private double stopTime;
	private double stoppedTime;
	private double speed;

	public Train(String id, Sim sim, int spawnStop, Line line, Vector3 color, double speed) { 

		super(id, line.getStop(spawnStop).getPos(), color, DEFAULT_TRAIN_SIZE);
		this.sim = sim;
		this.line = line;
		this.stop = spawnStop;
		this.speed = speed;
		this.status = TransitStatus.SPAWN;

	}

	public void updatePosAlongLine() {

		double modSpeed = speed * sim.getTimeIncrement();
		nextStop = (this.stop+1) % line.getLength();
		globalTime += sim.getTimeIncrement();
		stopTime += modSpeed;

		if (stopTime >= this.line.getDist(nextStop)) {

			// go to next station
			if (stoppedTime >= DEFAULT_STOP_DURATION) {

				this.status = TransitStatus.ON_TRAIN;
				this.getNextStop().removeTrain(this);
				stopTime = 0;
				stoppedTime = 0;
				stop = nextStop;
				setPos(this.line.getStop(stop).getPos());

			// wait at station
			} else {

				this.status = TransitStatus.WAITING_AT_STATION;
				this.getNextStop().addTrain(this);
				stoppedTime += sim.getTimeIncrement();

			}

		} else {

			// move along line
			this.status = TransitStatus.ON_TRAIN;
			setPos(Vector2.lerp(stopTime/this.line.getDist(nextStop), this.line.getStop(stop).getPos(), this.line.getStop(nextStop).getPos()));

		}

	}

	public void setSpeed(double speed) { this.speed = speed; }
	public TransitStatus getStatus() { return this.status; }
	public Line getLine() { return this.line; }
	public Node getStop() { return this.line.getStop(this.stop); }
	public Node getNextStop() { return this.line.getStop(this.nextStop); }
	public Node getRealNextStop() { return this.line.getStop((this.stop + 2) % this.line.getLength()); }
	public int getStopIndex() { return this.stop; }
	public double getGlobalTime() { return this.globalTime; }
	public double getStopTime() { return this.stopTime; }
	public double getStoppedTime() { return this.stoppedTime; }
	public double getSpeed() { return this.speed; }
	public String toString() { return "Train id=" + this.getID() + " Line=" + this.getLine().getID() + " pos=" + this.getPos(); }

}

class Line {

	public static int DEFAULT_TRAIN_SPAWN_SPACING = 8;
	public static Line WALKING_LINE = new Line("Transfer");

	private String id;
	private Vector3 color;

	private Node[] stops;
	private double[] dists;
	private Train[] trains;

	public Line(String id) {

		this.id = id;

	}

	public void addStop(Node stop, double dist) {

		if (this.stops == null) { this.stops = new Node[0]; }
		if (this.dists == null) { this.dists = new double[0]; }
		Node[] newStops = new Node[this.stops.length + 1];
		double[] newDists = new double[this.stops.length + 1];
		assert this.stops.length == this.dists.length;

		for (int i = 0; i < this.stops.length; i++) {

			newStops[i] = this.stops[i];
			newDists[i] = this.dists[i];

		}

		newStops[newStops.length-1] = stop;
		newDists[newDists.length-1] = dist;

		this.stops = newStops;
		this.dists = newDists;

	}

	// reconfigure stop list according to preset stop pattern
	public void rearrangeStops(String[] stopConfig) {

		ArrayList<Node> newStops = new ArrayList<Node>();
		for (String s : stopConfig) {

			for (Node n : this.stops) {

				if (n.getID().equals(s.replaceAll("_", " "))) {

					newStops.add(n);

				}

			}

		}

		int size = newStops.size();
		for (int i = 1; i < size; i++) {

			newStops.add(newStops.get(size-1-i));

		}

		dists = new double[newStops.size()];
		for (int i = 0; i < newStops.size()-1; i++) {

			double dist = Vector2.distanceBetween(newStops.get(i).getPos(), newStops.get(i+1).getPos());
			dists[i+1] = dist;
			Node.addNeighborPair(newStops.get(i), newStops.get(i+1), dist + Node.DEFAULT_CONST_STOP_PENALTY, this);

		}

		stops = new Node[newStops.size()];
		for (int i = 0; i < stops.length; i++) {

			stops[i] = newStops.get(i);

		}

	}

	// set stop colors to line color
	public void overrideStopColors() {

		for (int i = 0; i < this.stops.length; i++) {

			this.stops[i].setColor(color);

		}

	}

	public void setStops(Node[] stops, double[] dists) { this.stops = stops; this.dists = dists; }
	public void setTrains(Train[] trains) { this.trains = trains; }
	public void setColor(Vector3 col) { this.color = col; }
	public void setID(String id) { this.id = id; }
	public Node[] getStops() { return this.stops; }
	public double[] getDists() { return this.dists; }
	public Node getStop(int i) { return this.stops[i]; }
	public double getDist(int i) { return this.dists[i]; }
	public int getLength() { return this.stops.length; }
	public Train[] getTrains() { return this.trains; }
	public Vector3 getColor() { return this.color; }
	public String getID() { return this.id; }
	public String toString() { return "Line id= " + this.id; }

}

class Vector3Mod extends Vector3 {

	Vector3Mod(String hex) {

		this.x = (double)Integer.parseInt(hex.substring(0, 2), 16)/255.0;
		this.y = (double)Integer.parseInt(hex.substring(2, 4), 16)/255.0;
		this.z = (double)Integer.parseInt(hex.substring(4, 6), 16)/255.0;

	}

}
