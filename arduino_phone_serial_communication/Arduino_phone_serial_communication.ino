const int sensorpin=A0;
int data;
int time=0;
void setup() {
  Serial.begin(9600);
}

void loop() {
  data=analogRead(sensorpin);
  Serial.println('|' + String(data)+';');
  delay(100);
  time += 1;
  Serial.flush();
}
