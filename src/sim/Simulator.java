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

// TODO reminder for blog post throughout

/* TODO:
 * - fix citizen pathfinding & pathfollowing
 * - click-to-spawn citizens
 * 	- will need to temporarily create additional nodes at points, generate neighbors, then incorporate those into pathfinding mechanisms
 * - train spawn frequencies built into savefile
 * - have trains visually travel along ComplexLines
 * 	- idea: have them hook onto the nearest ComplexLine that is part of their line, then travel along it, then continue
 * 		(ComplexLines would have to have head and tail nodes with pos's which can then be checked using SegmentedNodes (would need to be added to that first))
 * 		algorithm will probably require tracking at least 1st-most-recently visited ComplexLine
 * 		this should also ideally be done in setup() otherwise it's very computationally expensive considering that paths don't change
 * - ability to click on trains and citizens to see their paths
 * 		this could prove very computationally expensive, don't want to check every citizen and train but also don't want to update segments
 * - clean up some code
 * - better logging (Logger class with verbosity levels?)
 * - better documentation
 * - multithreading ??
 * - zoom to mouse (work out math) & scaling panning to zoom
 * - map rotation ??
 * - background geography ??
 * - simulation statistics (+ graphing ??) ??
 * - time-based pathfinding (not just distance-based, but using train arrival times) ??
 */

public class Simulator {

	public static void main(String [] args) {

		// app initialization
		App app = new Sim(473, 0.5, new Vector3(0.05, 0.0, 10.0), new Vector3(150, 180, 15), new Vector2(0.9, 1), new Vector2(64, 64), Vector3.white, 1024);

		app.run();

	}

}

class Sim extends App {

	public static final int DEFAULT_CITIZEN_ALLOCATION = 10000;
	public static final int DEFAULT_TRAIN_ALLOCATION = 8;

	private Line[] lines;
	private Node[] nodes;
	private ArrayList<ArrayList<ArrayList<Node>>> segmentedNodes;
	private ComplexLine[] complexLines;
	private int numStops;
	private int nodeSegmentSize;
	private int ridershipTotal;

	private ArrayList<Citizen> citizens;
	
	private boolean paused;

	private double globalTime;
	private double timeIncrement;
	private double tempTimeIncrement;
	private double TIME_INCREMENT_INCREMENT;
	private double MIN_TIME_INCREMENT;
	private double MAX_TIME_INCREMENT;

	private double MAP_X_SCALE;
	private double MAP_Y_SCALE;

	private Vector2 mouseInitialPos;

	public Sim(int numStops, double timeIncrement, Vector3 INCREMENT_SETTINGS, Vector3 WORLD_SIZE, Vector2 MAP_X_Y_SCALE, Vector2 windowTopLeft, Vector3 backgroundColor, int windowHeight) {

		assert WORLD_SIZE.x % WORLD_SIZE.z <= 0.0001 && WORLD_SIZE.y % WORLD_SIZE.z <= 0.0001;

		this.numStops = numStops;
		this.timeIncrement = timeIncrement;
		this.tempTimeIncrement = timeIncrement;
		this.paused = false;
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

	}

	void setup() {

		// mouse wheel zooming
		this.addMouseWheelListener( new MouseAdapter() {
			@Override public void mouseWheelMoved(MouseWheelEvent e) {

				Drawable.adjustZoom(-e.getWheelRotation() * Drawable.ZOOM_CONST);

			}
		});

		// initialize zooming and panning variables
		mouseInitialPos = new Vector2(0, 0);
		Drawable.resetPanZoom();

		// iterate through stations and add stops to appropriate lines
		nodes = new Node[numStops];
		double[] stationX = new double[numStops];
		double[] stationY = new double[numStops];

		HashMap<String, Line> lines = new HashMap<String, Line>();

		int c = 0;
		ridershipTotal = 0;
		try (BufferedReader reader = new BufferedReader(new FileReader("src/sim/stations_data.csv")) ) {

			String line;

			while ((line = reader.readLine()) != null) {

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
				nodes[c] = stop;
				stationX[c] = Double.parseDouble(n[2]);
				stationY[c] = Double.parseDouble(n[3]);
				c++;

				// XXX this is very slow, find a better way
				for (String str : stopLines) {

					lines.get(str).addStop(stop, 1);

				}

			}

		} catch (IOException e) { assert false; }

		// convert real-world geometry data to world units
		Vector2 xMinMax = getMinMax(stationX);
		Vector2 yMinMax = getMinMax(stationY);
		normalize(stationX, -this._windowWidthInWorldUnits * MAP_X_SCALE, this._windowWidthInWorldUnits * MAP_X_SCALE, xMinMax.x, xMinMax.y);
		normalize(stationY, -this._windowHeightInWorldUnits * MAP_Y_SCALE, this._windowHeightInWorldUnits * MAP_Y_SCALE, yMinMax.x, yMinMax.y);

		for (int i = 0; i < stationX.length; i++) {

			nodes[i].setPos(stationX[i], stationY[i]);

		}

		// used for easiest n-nearest detection throughout
		segmentedNodes = new ArrayList<ArrayList<ArrayList<Node>>>();

		for (int i = 0; i < (int) this._windowWidthInWorldUnits / nodeSegmentSize + 3; i++) {

			segmentedNodes.add(new ArrayList<ArrayList<Node>>());
			for (int j = 0; j < (int) this._windowHeightInWorldUnits / nodeSegmentSize + 3; j++) {

				segmentedNodes.get(i).add(new ArrayList<Node>());

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

					for (Node n2 : segmentedNodes.get(n.getXSegmentIndex()+i).get(n.getYSegmentIndex()+j)) {

						double dist = Vector2.distanceBetween(n.getPos(), n2.getPos());
						if (dist <= Node.DEFAULT_TRANSFER_MAX_DIST) {

							Node.addNeighborPair(n, n2, dist * Node.DEFAULT_TRANSFER_WEIGHT + Node.DEFAULT_CONST_TRANSFER_PENALTY, Line.WALKING_LINE);

						}

					}

				}

			}

		}

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
				
				// XXX this is very slow, find a better way besides constantly rewriting array
				for (int i = 0; i < l.getLength(); i += Line.DEFAULT_TRAIN_SPAWN_SPACING) {

					l.addTrain(new Train(c+"", this, i, l, l.getColor(), Train.DEFAULT_TRAIN_SPEED));
					c++;

				}

			} catch (Exception e) {

				System.out.println("Caught invalid line " + l.getID());
				assert false;
				
			}

		}

		System.out.println("Generated lines " + lines.keySet());

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

		citizens = new ArrayList<Citizen>(Sim.DEFAULT_CITIZEN_ALLOCATION);

		// debug
		for (int i = 0; i < 10; i++) {
			
			citizens.add(new Citizen(this, Node.findPath(sample(nodes, ridershipTotal), sample(nodes, ridershipTotal))));
			System.out.println(Arrays.toString(citizens.get(i).getPath()) + "\n");
			
		}
		
	}

	void loop() {

		this.globalTime += timeIncrement;

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
		
		// XXX these are not working when timeInterval is adjusted
		// despawn citizens
		if (globalTime % Citizen.DESPAWN_INTERVAL <= 0.0001) {
			
			for (int i = 0; i < citizens.size(); i++) {
				
				// TODO update garbage collection mechanic (mostly higher threshold)
				/* debug
				if (citizens.get(i).getGlobalTime() >= Citizen.MAX_TIME_ALIVE) {
					
					citizens.get(i).getCurrentNode().removeCitizen(); // this may cause excessive removals
					citizens.remove(i);
					continue;
					
				}
				*/
				
				if (citizens.get(i).getStatus().equals(TransitStatus.DESPAWN)) {
					
					citizens.remove(i);
					
				}
				
			}
			
		}
		
		/* debug temporarily removed (also, consider lowering the spawn interval)
		// spawn citizens
		if (globalTime % Citizen.SPAWN_INTERVAL <= 0.0001) {
			
			for (int i = 0; i < (int) (Math.random() * Citizen.SPAWN_RANGE); i++) {
				
				citizens.add(new Citizen(this, Node.findPath(sample(nodes, ridershipTotal), sample(nodes, ridershipTotal))));
				
			}
			
		}
		*/

		// draw game objects
		for (ComplexLine cl : complexLines) {

			Drawable.drawComplexLine(this, cl);

		}

		for (Node n : nodes) {

			Drawable.drawCircle(this, n);

		}
		
		for (Citizen c : citizens) {

			c.followPath();
			if (true || c.getStatus().equals(TransitStatus.WALKING)) { // debug

				Drawable.drawCircle(this, c);

			}

		}
		
		// System.out.println(citizens.size()); // debug

		for (Line l : lines) {

			for (Train t : l.getTrains()) {

				t.updatePosAlongLine();
				Drawable.drawCircle(this, t);
				Drawable.drawString(this, t, t.getLine().getID(), Vector3.black, Train.FONT_SIZE_CONST, Train.FONT_CENTERED);

			}

		}

	}

	public Node findNode(String id, boolean contains) {

		for (Node n : nodes) {

			if (contains) { 

				if (n.getID().contains(id)) { return n; }

			} else {

				if (n.getID().equals(id)) { return n; }

			}

		}

		return null;

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

}

class Drawable {

	// TODO considering adding methods to allow these to be changed
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

	public static void adjustPan(double x, double y) {

		pan = pan.minus(new Vector2(x, y));
		constrictPan(pan);

	}

	public static void constrictPan(Vector2 pan) {

		pan.x = constrict(pan.x, -PAN_X_MINMAX, PAN_X_MINMAX);
		pan.y = constrict(pan.y, -PAN_Y_MINMAX, PAN_Y_MINMAX);

	}

	public static double constrict(double d, double min, double max) {

		return Math.min(Math.max(d, min), max);

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
	public void setColor(Vector3 col) { this.color = col; }
	public void setSize(double size) { this.size = size; }
	public String getID() { return this.id; }
	public Vector2 getPos() { return this.pos; }
	public double getX() { return this.pos.x; }
	public double getY() { return this.pos.y; }
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
	public void removeCitizen() { this.numCitizens = Math.max(0, this.numCitizens--); }
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

	private int xSegmentIndex;
	private int ySegmentIndex;

	public Node(Vector2 pos, Vector3 color, int ridership) {

		super("", pos, color, DEFAULT_NODE_SIZE);
		clear();
		this.score = 0; this.ridership = ridership;

	}

	public Node(Vector2 pos, Vector3 color, int ridership, double size) {

		super("", pos, color, size);
		clear();
		this.score = 0; this.ridership = ridership;

	}

	public Node(String id, Vector2 pos, Vector3 color, int ridership) {

		super(id, pos, color, DEFAULT_NODE_SIZE);
		clear();
		this.score = 0; this.ridership = ridership;

	}

	public Node(String id, Vector2 pos, Vector3 color, int ridership, double size) {

		super(id, pos, color, size);
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

	// XXX line-based path generation still not working 100% as-intended
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
	public void setSegmentIndex(int x, int y) { this.xSegmentIndex = x; this.ySegmentIndex = y; }
	public HashMap<PathWrapper, Double> getNeighbors() { return this.neighbors; }
	public HashMap<String, Train> getCurrentTrains() { return this.currentTrains; }
	public double getRidership() { return this.ridership; }
	public int getXSegmentIndex() { return this.xSegmentIndex; }
	public int getYSegmentIndex() { return this.ySegmentIndex; }
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
	private double length;

	public ComplexLine(String id, Vector3 col, Node[] nodes, double length) {

		super(id, nodes[0].getPos(), col, -1);
		this.nodes = nodes;
		this.length = length;

	}

	public void setNodes(Node[] nodes) { this.nodes = nodes; }
	public void setLength(double length) { this.length = length; }
	public Node[] getNodes() { return this.nodes; }
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
	public static final double DEFAULT_CITIZEN_SIZE = 2; // debug 0.25
	public static final double DEFAULT_CONTAINER_CITIZEN_SIZE = 0.05;
	public static final double DEFAULT_UNLOAD_TIME = Train.DEFAULT_STOP_DURATION / 3;
	public static final double DEFAULT_CITIZEN_SPEED = 0.2;
	public static final double DESPAWN_INTERVAL = 2;
	public static final double MAX_TIME_ALIVE = 20; // XXX does not scale with different TimeIntervals
	public static final double SPAWN_INTERVAL = 1;
	public static final int SPAWN_RANGE = 1000;

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
	private double speed;

	public Citizen(Sim sim) {

		super(new Vector2(), Citizen.DEFAULT_CITIZEN_COLOR, Citizen.DEFAULT_CITIZEN_SIZE);
		this.sim = sim;
		clearVariables();
	}

	public Citizen(Sim sim, Node.PathWrapper[] path) {

		super(new Vector2(), Citizen.DEFAULT_CITIZEN_COLOR, Citizen.DEFAULT_CITIZEN_SIZE);
		this.sim = sim;
		this.path = path;
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

	// XXX MAJORITY OF CITIZENS (~85%) ARE GETTING STUCK!!! NEED TO ANALYZE!
	// XXX CITIZEN TRACKING FOR NODES/TRAINS MAY BE BROKEN
	public void followPath() {
				
		if (status == TransitStatus.DESPAWN) { return; }
		
		if (actionTime == 0 && pathIndex == 0) {

			setPos(path[0].getNode().getPos());

		}

		double modSpeed = speed * sim.getTimeIncrement();
		globalTime += sim.getTimeIncrement();
		actionTime += sim.getTimeIncrement();

		if (pathIndex == path.length) {

			this.currentNode.removeCitizen(); // XXX this may cause excessive removes
			status = TransitStatus.DESPAWN;
			return;

		}

		nextNode = path[pathIndex].getNode();
		nextLine = path[pathIndex].getLine();

		switch (this.status) {

		case WALKING:
			if (walkTime == 0) {
				
				walkDist = Vector2.distanceBetween(this.getPos(), nextNode.getPos());
				
			}
			if (walkTime >= walkDist) {
				
				// at station, ready to proceed to next path node
				walkTime = 0;
				moveAlongPath();
				if (currentLine == Line.WALKING_LINE) {

					status = TransitStatus.WALKING;

				} else {

					status = TransitStatus.WAITING_AT_STATION;
					// XXX this seems to cause excessive adds? or at least these adds are not removed
					this.currentNode.addCitizen();
					
				}

			} else {

				// walk to station
				// XXX this moves non-linearly for some reason
				setPos(Vector2.lerp(walkTime/walkDist, this.getPos(), nextNode.getPos()));
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
			// XXX a big problem is happening here: either with getCurrentTrains, getRealNextStop, or equals
			for (Train t : this.currentNode.getCurrentTrains().values()) {

				// path-taking is greedy (e.g. takes any available train to next stop)
				if (t.getRealNextStop().equals(nextNode)) {

					System.out.println("HIT from " + currentNode.getID() + " to " + nextNode.getID()); // debug
					// ready to board train
					this.currentNode.removeCitizen();
					currentTrain = t;
					this.currentTrain.addCitizen();
					currentLine = t.getLine();
					nextNode = t.getRealNextStop();
					status = TransitStatus.ON_TRAIN;
					justBoarded = true;
					break;

				}

			}
			break;
		case ON_TRAIN:
			if (justBoarded && currentTrain.getStatus().equals(TransitStatus.ON_TRAIN)) { justBoarded = false; }
			if (!justBoarded && currentTrain.getStatus().equals(TransitStatus.WAITING_AT_STATION) && currentTrain.getStop().equals(currentNode)) {

				pathIndex++;
				if (pathIndex == path.length) {
					
					this.currentTrain.removeCitizen();
					status = TransitStatus.DESPAWN;
					return;
					
				}
				
				Node tmpNode = currentNode;
				currentNode = nextNode;
				currentLine = nextLine;
				nextNode = path[pathIndex].getNode();
				nextLine = path[pathIndex].getLine();
				
				if (!currentLine.equals(currentTrain.getLine())) {

					// at station and transfer required, ready to proceed to next path node
					this.currentTrain.removeCitizen();
					if (currentLine.equals(Line.WALKING_LINE)) {
						
						status = TransitStatus.WALKING;
						
					} else {
						
						status = TransitStatus.LINE_TRANSFER;
						tmpNode.addCitizen();
						
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
				currentNode = nextNode;
			}
			break;
		default:
			break;
			
		}

	}

	// TODO rework + clean up (e.g. messy calls to this function, repeated code, add more comments, etc.)
	private void moveAlongPath() {

		actionTime = 0;
		currentNode = nextNode;
		currentLine = nextLine;
		pathIndex++;
		if (pathIndex == path.length) {
			
			this.currentNode.removeCitizen();
			this.status = TransitStatus.DESPAWN;
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
	public void setPath(Node.PathWrapper[] path) { assert path != null & path.length >= 1; this.path = path; }
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

	public static final double DEFAULT_TRAIN_SIZE = 1.0;
	public static final double DEFAULT_STOP_DURATION = 6;
	public static final double DEFAULT_TRAIN_SPEED = 1.0;
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
	public static Line WALKING_LINE = new Line("Transfer", null, null, null);

	private String id;
	private Vector3 color;

	private Node[] stops;
	private double[] dists;
	private Train[] trains;

	public Line(String id) {

		this.id = id;

	}

	public Line(String id, Node[] stops, double[] dists, Train[] trains) {

		this(id);
		this.stops = stops;
		this.dists = dists;
		this.trains = trains;

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

	public void addTrain(Train train) {

		if (this.trains == null) { this.trains = new Train[0]; }
		Train[] trainsNew = new Train[this.trains.length + 1];

		for (int i = 0; i < this.trains.length; i++) {

			trainsNew[i] = this.trains[i];

		}

		trainsNew[trainsNew.length-1] = train;
		this.trains = trainsNew;

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
