program TestClassAndObject;

type
  MyClass = class;
  public
    X: Integer;
  end;

var
  Obj: MyClass;

begin
  Obj := MyClass.Create;
  Obj.X := 10;
  WRITELN(Obj.X);
  WRITELN('class end');
end.